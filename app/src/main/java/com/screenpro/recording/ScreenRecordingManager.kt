package com.screenpro.recording

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.screenpro.recording.camera.CameraCaptureManager
import com.screenpro.recording.compositor.ScreenCameraCompositor
import com.screenpro.storage.MediaStoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ScreenRecordingManager
 * Manages MediaRecorder, VirtualDisplay, and audio/video pipeline for screen capture.
 */
class ScreenRecordingManager(private val context: Context) {

    private val tag = "ScreenRecordingManager"
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentOutputFile: File? = null
    private val mediaStoreRepository = MediaStoreRepository(context)

    // Facecam compositing pipeline
    private var compositor: ScreenCameraCompositor? = null
    private var cameraCaptureManager: CameraCaptureManager? = null

    fun setMediaProjection(projection: MediaProjection?) {
        this.mediaProjection = projection
    }

    fun updateFaceCamPosition(xPercent: Float, yPercent: Float) {
        compositor?.updatePosition(xPercent, yPercent)
    }

    fun updateFaceCamConfig(
        enabled: Boolean,
        shape: String,
        posX: Float,
        posY: Float,
        scale: Float,
        borderWidthDp: Int,
        borderColorHex: String,
        isMirrored: Boolean
    ) {
        compositor?.updateConfig(
            enabled = enabled,
            shape = shape,
            posX = posX,
            posY = posY,
            scale = scale,
            borderWidthDp = borderWidthDp,
            borderColorHex = borderColorHex,
            isMirrored = isMirrored
        )
    }

    fun startRecording(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        enableMic: Boolean,
        enableFaceCam: Boolean = false,
        cameraShape: String = "circle",
        cameraPositionX: Float = 0.75f,
        cameraPositionY: Float = 0.08f,
        cameraScale: Float = 0.26f,
        cameraBorderWidth: Int = 3,
        cameraBorderColor: String = "#FF4B2B",
        cameraMirrored: Boolean = true
    ) {
        val projection = mediaProjection ?: run {
            Log.e(tag, "Cannot start recording: MediaProjection is null")
            RecordingController.setError("MediaProjection not initialized")
            return
        }

        try {
            val outputDir = context.cacheDir
            currentOutputFile = File(outputDir, "ScreenPro_${System.currentTimeMillis()}.mp4")

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            if (enableMic) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (enableMic) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setAudioSamplingRate(44100)
            }

            // Ensure dimensions are even numbers (required by H264 encoders)
            val encWidth = if (width % 2 == 0) width else width - 1
            val encHeight = if (height % 2 == 0) height else height - 1

            recorder.setVideoSize(encWidth, encHeight)
            recorder.setVideoFrameRate(fps.coerceIn(15, 60))
            recorder.setVideoEncodingBitRate(bitrate.coerceIn(1_000_000, 30_000_000))
            recorder.setOutputFile(currentOutputFile!!.absolutePath)

            recorder.prepare()

            val densityDpi = context.resources.displayMetrics.densityDpi

            if (enableFaceCam) {
                // Initialize Hardware Compositor
                val comp = ScreenCameraCompositor(
                    outputSurface = recorder.surface,
                    videoWidth = encWidth,
                    videoHeight = encHeight,
                    targetFps = fps
                ).apply {
                    updateConfig(
                        enabled = true,
                        shape = cameraShape,
                        posX = cameraPositionX,
                        posY = cameraPositionY,
                        scale = cameraScale,
                        borderWidthDp = cameraBorderWidth,
                        borderColorHex = cameraBorderColor,
                        isMirrored = cameraMirrored
                    )
                    start()
                }
                compositor = comp

                // Stream Screen into Compositor's screen input surface
                virtualDisplay = projection.createVirtualDisplay(
                    "ScreenProCaptureDisplay",
                    encWidth,
                    encHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    comp.screenSurface,
                    null,
                    null
                )

                // Stream Camera into Compositor's camera input surface
                comp.cameraSurface?.let { camSurf ->
                    cameraCaptureManager = CameraCaptureManager(context).apply {
                        startCapture(camSurf)
                    }
                }
            } else {
                // Direct recording path: zero overhead for standard screen capture
                virtualDisplay = projection.createVirtualDisplay(
                    "ScreenProCaptureDisplay",
                    encWidth,
                    encHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.surface,
                    null,
                    null
                )
            }

            recorder.start()
            this.mediaRecorder = recorder
            Log.d(tag, "Recording started successfully (facecam=$enableFaceCam): ${currentOutputFile?.name}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start screen recording", e)
            RecordingController.setError("Failed to start recording: ${e.localizedMessage}")
            release()
        }
    }

    fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.pause()
                Log.d(tag, "Recording paused")
            } catch (e: Exception) {
                Log.e(tag, "Failed to pause recording", e)
            }
        }
    }

    fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.resume()
                Log.d(tag, "Recording resumed")
            } catch (e: Exception) {
                Log.e(tag, "Failed to resume recording", e)
            }
        }
    }

    fun stopRecording(onComplete: (Boolean, Uri?) -> Unit) {
        val recorder = mediaRecorder
        val tempFile = currentOutputFile

        try {
            recorder?.stop()
            recorder?.reset()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping MediaRecorder: ${e.message}")
        }

        release()

        CoroutineScope(Dispatchers.IO).launch {
            var savedUri: Uri? = null
            var success = false

            if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
                savedUri = mediaStoreRepository.saveVideoToMediaStore(tempFile)
                success = savedUri != null
                try {
                    tempFile.delete()
                } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                onComplete(success, savedUri)
            }
        }
    }

    fun takeScreenshot() {
        val projection = mediaProjection ?: return
        val displayMetrics = context.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val vDisplay = projection.createVirtualDisplay(
            "ScreenProScreenshotDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)

                    CoroutineScope(Dispatchers.IO).launch {
                        mediaStoreRepository.saveScreenshotToMediaStore(croppedBitmap)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Screenshot processing error", e)
                } finally {
                    image.close()
                    reader.close()
                    vDisplay.release()
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun release() {
        try {
            cameraCaptureManager?.stopCapture()
        } catch (_: Exception) {}
        cameraCaptureManager = null

        try {
            compositor?.stop()
        } catch (_: Exception) {}
        compositor = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
    }
}

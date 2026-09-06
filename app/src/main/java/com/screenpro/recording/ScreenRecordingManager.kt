package com.screenpro.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.screenpro.recording.camera.CameraCaptureManager
import com.screenpro.recording.camera.DualCameraCaptureManager
import com.screenpro.recording.compositor.ScreenCameraCompositor
import com.screenpro.storage.MediaStoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ScreenRecordingManager
 * Manages MediaRecorder, VirtualDisplay, professional audio pipeline,
 * and multi-segment pause/resume recording with instant preview, Save and Continue.
 */
class ScreenRecordingManager(private val context: Context) {

    private val tag = "ScreenRecordingManager"
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentOutputFile: File? = null
    private val recordedSegments = mutableListOf<File>()
    private val mediaStoreRepository = MediaStoreRepository(context)

    // Cached parameters for continuous multi-segment recording ("Save and Continue")
    private var cachedParams: RecordingParams? = null

    data class RecordingParams(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val enableMic: Boolean,
        val enableFaceCam: Boolean = false,
        val cameraMode: String = "off", // "off", "facecam", "rear", "dual", "dual_only"
        val dualCameraLayout: String = "pip", // "pip", "split_horizontal", "split_vertical", "dual_bubbles"
        val cameraIsFront: Boolean = true,
        val cameraShape: String = "circle",
        val cameraPositionX: Float = 0.75f,
        val cameraPositionY: Float = 0.08f,
        val cameraScale: Float = 0.26f,
        val cameraBorderWidth: Int = 3,
        val cameraBorderColor: String = "#FF4B2B",
        val cameraMirrored: Boolean = true,
        val secondaryCameraShape: String = "circle",
        val secondaryCameraPositionX: Float = 0.08f,
        val secondaryCameraPositionY: Float = 0.08f,
        val secondaryCameraScale: Float = 0.22f,
        val secondaryCameraBorderWidth: Int = 3,
        val secondaryCameraBorderColor: String = "#00E5FF",
        val secondaryCameraMirrored: Boolean = false,
        val audioBitrate: Int = 192_000,
        val audioSampleRate: Int = 48_000,
        val audioChannels: Int = 2
    )

    // Facecam & Dual Camera compositing pipeline
    private var compositor: ScreenCameraCompositor? = null
    private var dualCameraCaptureManager: DualCameraCaptureManager? = null

    fun setMediaProjection(projection: MediaProjection?) {
        this.mediaProjection = projection
        try {
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(tag, "MediaProjection stopped by system")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(tag, "Failed to register callback on MediaProjection: ${e.message}")
        }
    }

    fun updateFaceCamPosition(xPercent: Float, yPercent: Float) {
        compositor?.updatePosition(xPercent, yPercent)
    }

    fun updateSecondaryFaceCamPosition(xPercent: Float, yPercent: Float) {
        compositor?.updateSecondaryPosition(xPercent, yPercent)
    }

    fun updateFaceCamConfig(
        enabled: Boolean,
        shape: String,
        posX: Float,
        posY: Float,
        scale: Float,
        borderWidthDp: Int,
        borderColorHex: String,
        isMirrored: Boolean,
        mode: String = "facecam",
        layout: String = "pip"
    ) {
        compositor?.updateConfig(
            enabled = enabled,
            shape = shape,
            posX = posX,
            posY = posY,
            scale = scale,
            borderWidthDp = borderWidthDp,
            borderColorHex = borderColorHex,
            isMirrored = isMirrored,
            mode = mode,
            layout = layout
        )
    }

    fun updateSecondaryFaceCamConfig(
        enabled: Boolean,
        shape: String,
        posX: Float,
        posY: Float,
        scale: Float,
        borderWidthDp: Int,
        borderColorHex: String,
        isMirrored: Boolean
    ) {
        compositor?.updateSecondaryConfig(
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
        cameraMode: String = if (enableFaceCam) "facecam" else "off",
        dualCameraLayout: String = "pip",
        cameraIsFront: Boolean = true,
        cameraShape: String = "circle",
        cameraPositionX: Float = 0.75f,
        cameraPositionY: Float = 0.08f,
        cameraScale: Float = 0.26f,
        cameraBorderWidth: Int = 3,
        cameraBorderColor: String = "#FF4B2B",
        cameraMirrored: Boolean = true,
        secondaryCameraShape: String = "circle",
        secondaryCameraPositionX: Float = 0.08f,
        secondaryCameraPositionY: Float = 0.08f,
        secondaryCameraScale: Float = 0.22f,
        secondaryCameraBorderWidth: Int = 3,
        secondaryCameraBorderColor: String = "#00E5FF",
        secondaryCameraMirrored: Boolean = false,
        audioBitrate: Int = 192_000,
        audioSampleRate: Int = 48_000,
        audioChannels: Int = 2
    ) {
        recordedSegments.clear()
        cachedParams = RecordingParams(
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            enableMic = enableMic,
            enableFaceCam = enableFaceCam,
            cameraMode = cameraMode,
            dualCameraLayout = dualCameraLayout,
            cameraIsFront = cameraIsFront,
            cameraShape = cameraShape,
            cameraPositionX = cameraPositionX,
            cameraPositionY = cameraPositionY,
            cameraScale = cameraScale,
            cameraBorderWidth = cameraBorderWidth,
            cameraBorderColor = cameraBorderColor,
            cameraMirrored = cameraMirrored,
            secondaryCameraShape = secondaryCameraShape,
            secondaryCameraPositionX = secondaryCameraPositionX,
            secondaryCameraPositionY = secondaryCameraPositionY,
            secondaryCameraScale = secondaryCameraScale,
            secondaryCameraBorderWidth = secondaryCameraBorderWidth,
            secondaryCameraBorderColor = secondaryCameraBorderColor,
            secondaryCameraMirrored = secondaryCameraMirrored,
            audioBitrate = audioBitrate,
            audioSampleRate = audioSampleRate,
            audioChannels = audioChannels
        )

        startRecorderInternal(cachedParams!!)
    }

    private fun startRecorderInternal(params: RecordingParams) {
        val projection = mediaProjection ?: run {
            if (params.cameraMode != "dual_only") {
                Log.e(tag, "Cannot start recording: MediaProjection is null")
                RecordingController.setError("MediaProjection not initialized")
                return
            }
            null
        }

        try {
            val outputDir = File(context.cacheDir, "screenrecorder_segments").apply { mkdirs() }
            val outputFile = File(outputDir, "ScreenRecorder_seg_${System.currentTimeMillis()}.mp4")
            currentOutputFile = outputFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            val hasMicPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            var audioConfigured = false
            if (params.enableMic && hasMicPermission) {
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                    audioConfigured = true
                } catch (e: Exception) {
                    Log.w(tag, "Microphone audio source unavailable, trying DEFAULT source: ${e.message}")
                    try {
                        recorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                        audioConfigured = true
                    } catch (e2: Exception) {
                        try {
                            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                            audioConfigured = true
                        } catch (e3: Exception) {
                            Log.w(tag, "All audio sources unavailable, falling back to video only: ${e3.message}")
                        }
                    }
                }
            }

            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (audioConfigured) {
                try {
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(params.audioBitrate.coerceIn(64_000, 320_000))
                    recorder.setAudioSamplingRate(params.audioSampleRate)
                    try {
                        recorder.setAudioChannels(params.audioChannels.coerceIn(1, 2))
                    } catch (e: Exception) {
                        Log.w(tag, "Stereo channels unsupported, defaulting to mono", e)
                        recorder.setAudioChannels(1)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to configure high quality audio encoder: ${e.message}")
                }
            }

            // Ensure dimensions are even numbers (required by H264 hardware encoders)
            val encWidth = if (params.width % 2 == 0) params.width else params.width - 1
            val encHeight = if (params.height % 2 == 0) params.height else params.height - 1

            recorder.setVideoSize(encWidth, encHeight)
            recorder.setVideoFrameRate(params.fps.coerceIn(15, 120))
            recorder.setVideoEncodingBitRate(params.bitrate.coerceIn(1_000_000, 50_000_000))
            recorder.setOutputFile(outputFile.absolutePath)

            recorder.prepare()

            val densityDpi = context.resources.displayMetrics.densityDpi
            val isCameraActive = params.enableFaceCam || (params.cameraMode != "off")
            val isDualOnly = (params.cameraMode == "dual_only")

            if (isCameraActive) {
                val comp = ScreenCameraCompositor(
                    outputSurface = recorder.surface,
                    videoWidth = encWidth,
                    videoHeight = encHeight,
                    targetFps = params.fps
                )
                comp.updateConfig(
                    enabled = true,
                    shape = params.cameraShape,
                    posX = params.cameraPositionX,
                    posY = params.cameraPositionY,
                    scale = params.cameraScale,
                    borderWidthDp = params.cameraBorderWidth,
                    borderColorHex = params.cameraBorderColor,
                    isMirrored = params.cameraMirrored,
                    mode = params.cameraMode,
                    layout = params.dualCameraLayout
                )
                if (params.cameraMode == "dual" || params.cameraMode == "dual_only") {
                    comp.updateSecondaryConfig(
                        enabled = true,
                        shape = params.secondaryCameraShape,
                        posX = params.secondaryCameraPositionX,
                        posY = params.secondaryCameraPositionY,
                        scale = params.secondaryCameraScale,
                        borderWidthDp = params.secondaryCameraBorderWidth,
                        borderColorHex = params.secondaryCameraBorderColor,
                        isMirrored = params.secondaryCameraMirrored
                    )
                }
                comp.start()
                this.compositor = comp

                if (!isDualOnly && projection != null) {
                    virtualDisplay = projection.createVirtualDisplay(
                        "ScreenRecorderCaptureDisplay",
                        encWidth,
                        encHeight,
                        densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        comp.screenSurface!!,
                        null,
                        null
                    )
                }

                val camMgr = DualCameraCaptureManager(context)
                this.dualCameraCaptureManager = camMgr
                if (params.cameraMode == "dual" || params.cameraMode == "dual_only") {
                    camMgr.startDualCapture(
                        primarySurface = comp.camera1Surface!!,
                        secondarySurface = comp.camera2Surface!!,
                        primaryIsFront = params.cameraIsFront
                    )
                } else {
                    camMgr.startSingleCapture(
                        targetSurface = comp.camera1Surface!!,
                        useFrontCamera = params.cameraIsFront
                    )
                }
            } else {
                virtualDisplay = projection?.createVirtualDisplay(
                    "ScreenRecorderCaptureDisplay",
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
            Log.d(tag, "Recording active for segment: ${outputFile.name}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start screen recording", e)
            RecordingController.setError("Failed to start recording: ${e.localizedMessage}")
            release()
        }
    }

    /**
     * Halts the active recorder, properly finalizes the MP4 segment (writing moov atom),
     * and keeps MediaProjection alive so user can preview and choose to "Save" or "Continue".
     */
    fun holdAndPreviewSegment(onSegmentReady: (File, Uri) -> Unit) {
        val recorder = mediaRecorder
        val segFile = currentOutputFile

        try {
            dualCameraCaptureManager?.stopCapture()
        } catch (_: Exception) {}
        dualCameraCaptureManager = null

        try {
            compositor?.stop()
        } catch (_: Exception) {}
        compositor = null

        try {
            recorder?.stop()
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping recorder for segment: ${e.message}")
        }
        mediaRecorder = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        if (segFile != null && segFile.exists() && segFile.length() > 0) {
            recordedSegments.add(segFile)
            val uri = Uri.fromFile(segFile)
            Log.d(tag, "Segment ready for preview: ${segFile.name} (size=${segFile.length()})")
            RecordingController.onSegmentReady(segFile, uri)
            onSegmentReady(segFile, uri)
        } else {
            Log.w(tag, "Current segment file is empty or missing")
        }
    }

    /**
     * Continues / resumes recording by starting the next video segment seamlessly.
     */
    fun continueRecordingSegment() {
        val params = cachedParams ?: run {
            Log.e(tag, "Cannot continue: cachedParams is null")
            return
        }
        RecordingController.onRecordingResumed()
        startRecorderInternal(params)
    }

    /**
     * Saves all recorded segments (merging if multiple), writes to MediaStore and Room,
     * then releases MediaProjection and completes.
     */
    fun saveAllSegmentsAndFinish(onComplete: (Boolean, Uri?) -> Unit) {
        // If a segment is currently running, finalize it first
        if (mediaRecorder != null) {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (e: Exception) {
                Log.w(tag, "Error finalizing active recorder: ${e.message}")
            }
            mediaRecorder = null

            try {
                virtualDisplay?.release()
            } catch (_: Exception) {}
            virtualDisplay = null

            currentOutputFile?.let {
                if (it.exists() && it.length() > 0 && !recordedSegments.contains(it)) {
                    recordedSegments.add(it)
                }
            }
        }

        val segmentsToSave = recordedSegments.filter { it.exists() && it.length() > 0 }
        release()

        CoroutineScope(Dispatchers.IO).launch {
            var savedUri: Uri? = null
            var success = false

            if (segmentsToSave.isNotEmpty()) {
                val fileToSave: File
                if (segmentsToSave.size == 1) {
                    fileToSave = segmentsToSave[0]
                } else {
                    val mergedFile = File(context.cacheDir, "ScreenRecorder_Merged_${System.currentTimeMillis()}.mp4")
                    val merged = VideoMerger.mergeVideos(segmentsToSave, mergedFile)
                    fileToSave = if (merged && mergedFile.exists() && mergedFile.length() > 0) {
                        mergedFile
                    } else {
                        segmentsToSave.last()
                    }
                }

                val title = "ScreenRecorder_${System.currentTimeMillis()}"
                val appItem = mediaStoreRepository.saveVideoToAppLibrary(fileToSave, title)
                try {
                    mediaStoreRepository.saveVideoToPhoneGallery(appItem)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to auto-save to gallery: ${e.message}")
                }
                savedUri = appItem.uri
                success = true

                // Clean up temporary segment files
                for (f in segmentsToSave) {
                    try { f.delete() } catch (_: Exception) {}
                }
                if (fileToSave != segmentsToSave[0]) {
                    try { fileToSave.delete() } catch (_: Exception) {}
                }
            }

            withContext(Dispatchers.Main) {
                RecordingController.onRecordingStopped()
                onComplete(success, savedUri)
            }
        }
    }

    fun discardRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
        } catch (_: Exception) {}

        release()

        for (f in recordedSegments) {
            try { f.delete() } catch (_: Exception) {}
        }
        recordedSegments.clear()
        currentOutputFile?.let {
            try { it.delete() } catch (_: Exception) {}
        }
        RecordingController.onRecordingStopped()
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
        saveAllSegmentsAndFinish(onComplete)
    }

    fun takeScreenshot() {
        val projection = mediaProjection ?: return
        val displayMetrics = context.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val vDisplay = try {
            projection.createVirtualDisplay(
                "ScreenRecorderScreenshotDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to create virtual display for screenshot", e)
            imageReader.close()
            return
        }

        var captured = false
        imageReader.setOnImageAvailableListener({ reader ->
            if (captured) return@setOnImageAvailableListener
            val image = reader.acquireLatestImage() ?: reader.acquireNextImage() ?: return@setOnImageAvailableListener
            captured = true
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
                if (croppedBitmap != bitmap) {
                    bitmap.recycle()
                }

                CoroutineScope(Dispatchers.IO).launch {
                    val title = "Screenshot_${System.currentTimeMillis()}"
                    mediaStoreRepository.saveScreenshotToAppLibrary(croppedBitmap, title)
                    mediaStoreRepository.saveScreenshotToMediaStore(croppedBitmap, title)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Screenshot captured & saved to library!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Screenshot processing error", e)
            } finally {
                image.close()
                reader.close()
                vDisplay.release()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun release() {
        try {
            dualCameraCaptureManager?.stopCapture()
        } catch (_: Exception) {}
        dualCameraCaptureManager = null

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

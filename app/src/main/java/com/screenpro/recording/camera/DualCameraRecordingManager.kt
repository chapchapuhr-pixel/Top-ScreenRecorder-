package com.screenpro.recording.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.screenpro.data.model.MediaItem
import com.screenpro.recording.compositor.ScreenCameraCompositor
import com.screenpro.storage.MediaStoreRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * DualCameraRecordingManager
 * Orchestrates standalone front + rear concurrent camera preview and recording.
 * Supports:
 * - Picture-in-Picture (PiP) and Split Screen (horizontal & vertical)
 * - Corner snapping and continuous free drag positioning
 * - Shape clipping (circle, rounded-rectangle, rectangle)
 * - Lens swapping (Rear main <-> Front main)
 * - Front camera mirroring
 * - Dynamic resolution & orientation (Portrait / Landscape)
 * - Audio recording with automatic hardware fallback
 * - Hardware concurrent camera detection and graceful handling
 */
class DualCameraRecordingManager(private val context: Context) {

    private val tag = "DualCameraRecMgr"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Hardware support
    private val _supportInfo = MutableStateFlow(CameraCapabilityHelper.checkDualCameraSupport(context))
    val supportInfo: StateFlow<CameraCapabilityHelper.DualCameraSupportInfo> = _supportInfo.asStateFlow()

    // Recording state
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    // Mode: false = Rear Camera is Main (full screen), Front Camera is secondary/PiP
    //       true  = Front Camera is Main, Rear Camera is secondary/PiP
    private val _isFrontMain = MutableStateFlow(false)
    val isFrontMain: StateFlow<Boolean> = _isFrontMain.asStateFlow()

    // Layout: "pip", "split_vertical", "split_horizontal"
    private val _layout = MutableStateFlow("pip")
    val layout: StateFlow<String> = _layout.asStateFlow()

    // PiP Shape: "circle", "rounded-square", "rectangle"
    private val _pipShape = MutableStateFlow("circle")
    val pipShape: StateFlow<String> = _pipShape.asStateFlow()

    // PiP Corner: "top_right", "top_left", "bottom_right", "bottom_left", "custom"
    private val _pipCorner = MutableStateFlow("top_right")
    val pipCorner: StateFlow<String> = _pipCorner.asStateFlow()

    // Position (0.0f - 1.0f)
    private val _pipPosX = MutableStateFlow(0.76f)
    val pipPosX: StateFlow<Float> = _pipPosX.asStateFlow()

    private val _pipPosY = MutableStateFlow(0.08f)
    val pipPosY: StateFlow<Float> = _pipPosY.asStateFlow()

    // Scale (0.18f = small, 0.26f = medium, 0.36f = large)
    private val _pipScale = MutableStateFlow(0.26f)
    val pipScale: StateFlow<Float> = _pipScale.asStateFlow()

    // Settings
    private val _isMirrored = MutableStateFlow(true)
    val isMirrored: StateFlow<Boolean> = _isMirrored.asStateFlow()

    private val _isLandscape = MutableStateFlow(false)
    val isLandscape: StateFlow<Boolean> = _isLandscape.asStateFlow()

    private val _enableMic = MutableStateFlow(true)
    val enableMic: StateFlow<Boolean> = _enableMic.asStateFlow()

    // Internal components
    private var compositor: ScreenCameraCompositor? = null
    private var cameraManager: DualCameraCaptureManager? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    private var timerJob: Job? = null
    private var previewSurface: Surface? = null

    private val mediaStoreRepository = MediaStoreRepository(context)

    fun refreshSupport() {
        _supportInfo.value = CameraCapabilityHelper.checkDualCameraSupport(context)
    }

    /**
     * Attaches the on-screen preview Surface and starts the camera capture session.
     */
    fun attachPreviewSurface(surface: Surface) {
        this.previewSurface = surface

        val width = if (_isLandscape.value) 1920 else 1080
        val height = if (_isLandscape.value) 1080 else 1920

        val comp = ScreenCameraCompositor(
            outputSurface = null,
            videoWidth = width,
            videoHeight = height,
            targetFps = 30,
            previewSurface = surface
        ).apply {
            cameraMode = "dual_only"
            dualLayout = _layout.value
            camera1Enabled = true
            camera1Shape = "rectangle"
            camera1Mirrored = if (_isFrontMain.value) _isMirrored.value else false

            camera2Enabled = true
            camera2Shape = _pipShape.value
            camera2PositionX = _pipPosX.value
            camera2PositionY = _pipPosY.value
            camera2Scale = _pipScale.value
            camera2BorderWidthDp = 3
            camera2BorderColorHex = "#FF4B2B"
            camera2Mirrored = if (!_isFrontMain.value) _isMirrored.value else false
            start()
        }
        this.compositor = comp

        val cam = DualCameraCaptureManager(context)
        this.cameraManager = cam

        val sup = _supportInfo.value
        if (sup.isSupported && comp.camera1Surface != null && comp.camera2Surface != null) {
            cam.startDualCapture(
                primarySurface = comp.camera1Surface!!,
                secondarySurface = comp.camera2Surface!!,
                primaryIsFront = _isFrontMain.value
            )
        } else if (comp.camera1Surface != null) {
            // Graceful fallback to single camera preview
            cam.startSingleCapture(
                targetSurface = comp.camera1Surface!!,
                useFrontCamera = _isFrontMain.value
            )
        }
    }

    fun swapCameras() {
        val newFrontMain = !_isFrontMain.value
        _isFrontMain.value = newFrontMain

        val comp = compositor ?: return
        comp.camera1Mirrored = if (newFrontMain) _isMirrored.value else false
        comp.camera2Mirrored = if (!newFrontMain) _isMirrored.value else false

        val cam = cameraManager ?: return
        val sup = _supportInfo.value

        if (sup.isSupported && comp.camera1Surface != null && comp.camera2Surface != null) {
            cam.stopCapture()
            cam.startDualCapture(
                primarySurface = comp.camera1Surface!!,
                secondarySurface = comp.camera2Surface!!,
                primaryIsFront = newFrontMain
            )
        } else if (comp.camera1Surface != null) {
            cam.switchCameraLens(comp.camera1Surface!!, newFrontMain)
        }
    }

    fun setLayout(newLayout: String) {
        _layout.value = newLayout
        compositor?.dualLayout = newLayout
    }

    fun setPipShape(newShape: String) {
        _pipShape.value = newShape
        compositor?.camera2Shape = newShape
    }

    fun setPipCorner(corner: String) {
        _pipCorner.value = corner
        val (x, y) = when (corner) {
            "top_left" -> 0.08f to 0.08f
            "bottom_left" -> 0.08f to 0.76f
            "bottom_right" -> 0.76f to 0.76f
            else -> 0.76f to 0.08f // top_right
        }
        _pipPosX.value = x
        _pipPosY.value = y
        compositor?.updateSecondaryPosition(x, y)
    }

    fun setPipPosition(x: Float, y: Float) {
        _pipCorner.value = "custom"
        val clampedX = x.coerceIn(0.02f, 0.95f)
        val clampedY = y.coerceIn(0.02f, 0.95f)
        _pipPosX.value = clampedX
        _pipPosY.value = clampedY
        compositor?.updateSecondaryPosition(clampedX, clampedY)
    }

    fun setPipScale(scale: Float) {
        val clamped = scale.coerceIn(0.16f, 0.45f)
        _pipScale.value = clamped
        compositor?.camera2Scale = clamped
    }

    fun toggleMirror() {
        val next = !_isMirrored.value
        _isMirrored.value = next
        val comp = compositor ?: return
        if (_isFrontMain.value) {
            comp.camera1Mirrored = next
        } else {
            comp.camera2Mirrored = next
        }
    }

    fun toggleOrientation() {
        val next = !_isLandscape.value
        _isLandscape.value = next
        val prev = previewSurface ?: return
        compositor?.stop()
        cameraManager?.stopCapture()
        attachPreviewSurface(prev)
    }

    fun toggleMic() {
        _enableMic.value = !_enableMic.value
    }

    fun startRecording(onError: (String) -> Unit = {}) {
        if (_isRecording.value) return
        val sup = _supportInfo.value
        if (!sup.isSupported) {
            onError("Dual Camera isn't supported on this device.")
            return
        }

        try {
            val outputDir = File(context.cacheDir, "dual_cam_recordings").apply { mkdirs() }
            val file = File(outputDir, "DualCam_${System.currentTimeMillis()}.mp4")
            this.currentOutputFile = file

            val width = if (_isLandscape.value) 1920 else 1080
            val height = if (_isLandscape.value) 1080 else 1920

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // Audio configuration with resilient fallback
            val hasMicPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            var audioConfigured = false
            if (_enableMic.value && hasMicPermission) {
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                    audioConfigured = true
                } catch (e: Exception) {
                    Log.w(tag, "Mic audio source unavailable, trying DEFAULT: ${e.message}")
                    try {
                        recorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                        audioConfigured = true
                    } catch (e2: Exception) {
                        try {
                            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                            audioConfigured = true
                        } catch (e3: Exception) {
                            Log.w(tag, "All audio sources failed, recording video only: ${e3.message}")
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
                    recorder.setAudioEncodingBitRate(192_000)
                    recorder.setAudioSamplingRate(48_000)
                    recorder.setAudioChannels(2)
                } catch (e: Exception) {
                    Log.w(tag, "Failed high bitrate audio config, using default", e)
                }
            }

            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(12_000_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()

            val recordSurface = recorder.surface
            compositor?.setRecordSurface(recordSurface)

            recorder.start()
            this.mediaRecorder = recorder

            _isRecording.value = true
            _elapsedSeconds.value = 0L

            timerJob?.cancel()
            timerJob = scope.launch {
                while (isActive) {
                    delay(1000)
                    _elapsedSeconds.value += 1
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start dual camera recording", e)
            onError(e.message ?: "Failed to start dual recording")
            stopRecordingInternal()
        }
    }

    fun stopRecording(onComplete: (MediaItem?) -> Unit) {
        timerJob?.cancel()
        _isRecording.value = false

        scope.launch(Dispatchers.IO) {
            var mediaItem: MediaItem? = null
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.e(tag, "Error stopping MediaRecorder", e)
            } finally {
                compositor?.setRecordSurface(null)
                mediaRecorder?.release()
                mediaRecorder = null
            }

            val file = currentOutputFile
            if (file != null && file.exists() && file.length() > 0) {
                try {
                    val title = "ScreenRecorder_DualCam_${System.currentTimeMillis()}"
                    mediaItem = mediaStoreRepository.saveVideoToAppLibrary(file, title)
                    try {
                        mediaStoreRepository.saveVideoToPhoneGallery(mediaItem)
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to export to MediaStore: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed saving dual recording", e)
                }
            }

            withContext(Dispatchers.Main) {
                onComplete(mediaItem)
            }
        }
    }

    private fun stopRecordingInternal() {
        timerJob?.cancel()
        _isRecording.value = false
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        compositor?.setRecordSurface(null)
        mediaRecorder?.release()
        mediaRecorder = null
    }

    fun release() {
        stopRecordingInternal()
        compositor?.stop()
        compositor = null
        cameraManager?.stopCapture()
        cameraManager = null
        previewSurface = null
        scope.cancel()
    }
}

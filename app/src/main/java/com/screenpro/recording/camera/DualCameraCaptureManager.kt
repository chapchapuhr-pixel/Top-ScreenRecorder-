package com.screenpro.recording.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * DualCameraCaptureManager
 * Production-ready camera capture manager supporting:
 * 1. Single camera capture (Front or Rear FaceCam)
 * 2. Simultaneous Dual Camera capture (Front + Rear)
 * 3. Graceful fallback to single camera if dual streaming is unsupported or fails at runtime.
 */
class DualCameraCaptureManager(private val context: Context) {

    private val tag = "DualCameraCaptureMgr"

    // Primary Camera (e.g. Front FaceCam or Rear Camera)
    private var primaryCameraDevice: CameraDevice? = null
    private var primarySession: CameraCaptureSession? = null
    private val primaryLock = Semaphore(1)

    // Secondary Camera (e.g. Rear Camera or Front Cam in Dual Mode)
    private var secondaryCameraDevice: CameraDevice? = null
    private var secondarySession: CameraCaptureSession? = null
    private val secondaryLock = Semaphore(1)

    // Background Threading
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    interface DualCameraCallback {
        fun onPrimaryReady()
        fun onSecondaryReady()
        fun onDualCameraFallback(reason: String)
        fun onError(cameraIndex: Int, errorCode: Int, message: String)
    }

    var callback: DualCameraCallback? = null

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("DualCameraCaptureThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(1000)
            backgroundThread = null
            backgroundHandler = null
        } catch (_: InterruptedException) {}
    }

    /**
     * Starts single camera streaming into targetSurface.
     */
    @SuppressLint("MissingPermission")
    fun startSingleCapture(
        targetSurface: Surface,
        useFrontCamera: Boolean = true,
        onReady: (() -> Unit)? = null
    ) {
        startBackgroundThread()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val handler = backgroundHandler ?: return

        handler.post {
            try {
                if (!primaryLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    Log.e(tag, "Timeout locking primary camera opening")
                    return@post
                }

                val cameraId = getCameraIdByFacing(cameraManager, useFrontCamera)
                    ?: cameraManager.cameraIdList.firstOrNull()

                if (cameraId == null) {
                    Log.e(tag, "No camera found on device")
                    primaryLock.release()
                    return@post
                }

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        primaryLock.release()
                        primaryCameraDevice = camera
                        createCaptureSession(camera, targetSurface, isPrimary = true) {
                            onReady?.invoke()
                            callback?.onPrimaryReady()
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        primaryLock.release()
                        camera.close()
                        primaryCameraDevice = null
                        Log.w(tag, "Primary camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        primaryLock.release()
                        camera.close()
                        primaryCameraDevice = null
                        Log.e(tag, "Primary camera error: $error")
                        callback?.onError(1, error, "Camera error $error")
                    }
                }, handler)
            } catch (e: SecurityException) {
                Log.e(tag, "Camera permission not granted", e)
                primaryLock.release()
            } catch (e: Exception) {
                Log.e(tag, "Failed to open primary camera", e)
                primaryLock.release()
            }
        }
    }

    /**
     * Starts dual camera streaming into primarySurface and secondarySurface.
     */
    @SuppressLint("MissingPermission")
    fun startDualCapture(
        primarySurface: Surface,
        secondarySurface: Surface,
        primaryIsFront: Boolean = true,
        onBothReady: (() -> Unit)? = null
    ) {
        val dualInfo = CameraCapabilityHelper.checkDualCameraSupport(context)
        if (!dualInfo.isSupported) {
            Log.w(tag, "Concurrent dual camera not supported: ${dualInfo.statusMessage}. Falling back to single camera.")
            callback?.onDualCameraFallback(dualInfo.statusMessage)
            startSingleCapture(primarySurface, primaryIsFront) {
                onBothReady?.invoke()
            }
            return
        }

        startBackgroundThread()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val handler = backgroundHandler ?: return

        val primaryCamId = if (primaryIsFront) dualInfo.frontCameraId else dualInfo.rearCameraId
        val secondaryCamId = if (primaryIsFront) dualInfo.rearCameraId else dualInfo.frontCameraId

        if (primaryCamId == null || secondaryCamId == null) {
            Log.w(tag, "Cannot resolve both camera IDs. Falling back to single camera.")
            callback?.onDualCameraFallback("Both camera IDs could not be resolved.")
            startSingleCapture(primarySurface, primaryIsFront, onBothReady)
            return
        }

        var primaryConfigured = false
        var secondaryConfigured = false

        fun checkBothConfigured() {
            if (primaryConfigured && secondaryConfigured) {
                onBothReady?.invoke()
            }
        }

        handler.post {
            // 1. Open Primary Camera
            try {
                if (!primaryLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    Log.e(tag, "Timeout locking primary camera")
                    return@post
                }

                cameraManager.openCamera(primaryCamId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        primaryLock.release()
                        primaryCameraDevice = camera
                        createCaptureSession(camera, primarySurface, isPrimary = true) {
                            primaryConfigured = true
                            callback?.onPrimaryReady()
                            checkBothConfigured()
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        primaryLock.release()
                        camera.close()
                        primaryCameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        primaryLock.release()
                        camera.close()
                        primaryCameraDevice = null
                        callback?.onError(1, error, "Primary camera error: $error")
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(tag, "Failed to open primary camera in dual mode", e)
                primaryLock.release()
            }

            // 2. Open Secondary Camera
            try {
                if (!secondaryLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    Log.e(tag, "Timeout locking secondary camera")
                    return@post
                }

                cameraManager.openCamera(secondaryCamId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        secondaryLock.release()
                        secondaryCameraDevice = camera
                        createCaptureSession(camera, secondarySurface, isPrimary = false) {
                            secondaryConfigured = true
                            callback?.onSecondaryReady()
                            checkBothConfigured()
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        secondaryLock.release()
                        camera.close()
                        secondaryCameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        secondaryLock.release()
                        camera.close()
                        secondaryCameraDevice = null
                        Log.w(tag, "Secondary camera failed to open: $error. Falling back to single camera gracefully.")
                        callback?.onDualCameraFallback("Secondary camera unavailable (Error $error). Single camera mode active.")
                    }
                }, handler)
            } catch (e: Exception) {
                Log.w(tag, "Secondary camera open exception, falling back gracefully: ${e.message}")
                secondaryLock.release()
                callback?.onDualCameraFallback("Secondary camera exception: ${e.message}. Single camera mode active.")
            }
        }
    }

    private fun createCaptureSession(
        camera: CameraDevice,
        surface: Surface,
        isPrimary: Boolean,
        onConfigured: () -> Unit
    ) {
        val handler = backgroundHandler ?: return

        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (isPrimary) {
                        primarySession = session
                    } else {
                        secondarySession = session
                    }

                    try {
                        val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }

                        session.setRepeatingRequest(reqBuilder.build(), null, handler)
                        Log.d(tag, "Capture session repeating request started for ${if (isPrimary) "Primary" else "Secondary"} camera")
                        onConfigured()
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to start repeating request on session", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(tag, "Capture session configure failed for ${if (isPrimary) "Primary" else "Secondary"} camera")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(tag, "Exception creating capture session", e)
        }
    }

    private fun getCameraIdByFacing(cameraManager: CameraManager, isFront: Boolean): String? {
        try {
            val targetFacing = if (isFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    return id
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error searching camera facing", e)
        }
        return null
    }

    fun stopCapture() {
        try {
            primaryLock.acquire()
            primarySession?.close()
            primarySession = null
            primaryCameraDevice?.close()
            primaryCameraDevice = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing primary camera", e)
        } finally {
            primaryLock.release()
        }

        try {
            secondaryLock.acquire()
            secondarySession?.close()
            secondarySession = null
            secondaryCameraDevice?.close()
            secondaryCameraDevice = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing secondary camera", e)
        } finally {
            secondaryLock.release()
        }

        stopBackgroundThread()
        Log.d(tag, "DualCameraCaptureManager fully stopped and released")
    }

    fun switchCameraLens(targetSurface: Surface, useFrontCamera: Boolean) {
        stopCapture()
        startSingleCapture(targetSurface, useFrontCamera)
    }
}

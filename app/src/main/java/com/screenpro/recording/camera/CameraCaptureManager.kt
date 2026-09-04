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
 * CameraCaptureManager
 * Streams front camera frames into a provided Surface using Android Camera2 API.
 * Optimized for low-latency, background service operation with auto-exposure and auto-focus.
 */
class CameraCaptureManager(private val context: Context) {
    private val tag = "CameraCaptureManager"

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)
    private var currentSurface: Surface? = null

    @SuppressLint("MissingPermission")
    fun startCapture(targetSurface: Surface, onReady: (() -> Unit)? = null) {
        currentSurface = targetSurface
        startBackgroundThread()

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return

        val handler = backgroundHandler ?: return
        handler.post {
            try {
                if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    Log.e(tag, "Time out waiting to lock camera opening")
                    return@post
                }

                val frontCameraId = getFrontFacingCameraId(cameraManager) ?: cameraManager.cameraIdList.firstOrNull()
                if (frontCameraId == null) {
                    Log.e(tag, "No camera available on this device")
                    cameraOpenCloseLock.release()
                    return@post
                }

                cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraOpenCloseLock.release()
                        cameraDevice = camera
                        createCaptureSession(camera, targetSurface, onReady)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cameraOpenCloseLock.release()
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraOpenCloseLock.release()
                        camera.close()
                        cameraDevice = null
                        Log.e(tag, "CameraDevice error: $error")
                    }
                }, handler)
            } catch (e: SecurityException) {
                Log.e(tag, "Camera permission not granted", e)
                cameraOpenCloseLock.release()
            } catch (e: Exception) {
                Log.e(tag, "Failed to open camera", e)
                cameraOpenCloseLock.release()
            }
        }
    }

    private fun createCaptureSession(camera: CameraDevice, surface: Surface, onReady: (() -> Unit)?) {
        val handler = backgroundHandler ?: return

        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }

                        session.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                        Log.d(tag, "Camera capture session repeating request started successfully")
                        onReady?.invoke()
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to start camera repeating request", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(tag, "Camera capture session configuration failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(tag, "Failed to create camera capture session", e)
        }
    }

    private fun getFrontFacingCameraId(cameraManager: CameraManager): String? {
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return id
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error querying camera characteristics", e)
        }
        return null
    }

    fun stopCapture() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null
            currentSurface = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }
        Log.d(tag, "Camera capture stopped")
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("Camera2BackgroundThread").apply { start() }
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
}

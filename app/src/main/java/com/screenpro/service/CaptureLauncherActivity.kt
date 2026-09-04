package com.screenpro.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.screenpro.data.SettingsManager
import com.screenpro.recording.VideoResolutionHelper
import com.screenpro.storage.MediaStoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Headless translucent Activity that handles MediaProjection permission prompts
 * for the Floating Ball directly over whatever app/game the user is currently using,
 * without ever opening or foregrounding the main ScreenPro recorder application.
 */
class CaptureLauncherActivity : ComponentActivity() {

    companion object {
        const val ACTION_START_RECORD = "com.screenpro.ACTION_START_RECORD"
        const val ACTION_CAPTURE_SCREENSHOT = "com.screenpro.ACTION_CAPTURE_SCREENSHOT"
        const val ACTION_REQUEST_CAMERA_PERMISSION = "com.screenpro.ACTION_REQUEST_CAMERA_PERMISSION"

        fun startRecord(context: Context) {
            val intent = Intent(context, CaptureLauncherActivity::class.java).apply {
                action = ACTION_START_RECORD
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }

        fun captureScreenshot(context: Context) {
            val intent = Intent(context, CaptureLauncherActivity::class.java).apply {
                action = ACTION_CAPTURE_SCREENSHOT
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }

        fun requestCameraPermission(context: Context) {
            val intent = Intent(context, CaptureLauncherActivity::class.java).apply {
                action = ACTION_REQUEST_CAMERA_PERMISSION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val settingsManager = SettingsManager(applicationContext)
            val current = settingsManager.settings.value
            settingsManager.updateSettings(current.copy(cameraEnabled = true))
            com.screenpro.recording.FaceCamController.setFaceCamEnabled(true)
            Toast.makeText(this, "Camera permission granted. FaceCam activated!", Toast.LENGTH_SHORT).show()
            val floatingIntent = Intent(this, FloatingBallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(floatingIntent)
            } else {
                startService(floatingIntent)
            }
        } else {
            Toast.makeText(this, "Camera permission is required to show FaceCam", Toast.LENGTH_SHORT).show()
        }
        finishWithNoAnimation()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            when (intent?.action) {
                ACTION_CAPTURE_SCREENSHOT -> {
                    performInstantScreenshot(result.resultCode, result.data!!)
                }
                else -> {
                    startRecordingService(result.resultCode, result.data!!)
                    finishWithNoAnimation()
                }
            }
        } else {
            Toast.makeText(this, "Screen capture was cancelled", Toast.LENGTH_SHORT).show()
            finishWithNoAnimation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        // Make window completely transparent and non-intrusive
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        if (intent?.action == ACTION_REQUEST_CAMERA_PERMISSION) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            return
        }

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager == null) {
            Toast.makeText(this, "Media projection not supported on this device", Toast.LENGTH_SHORT).show()
            finishWithNoAnimation()
            return
        }

        try {
            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to request screen capture: ${e.message}", Toast.LENGTH_SHORT).show()
            finishWithNoAnimation()
        }
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val settingsManager = SettingsManager(applicationContext)
        val settings = settingsManager.settings.value

        val (width, height) = VideoResolutionHelper.getVideoDimensions(applicationContext, settings)

        val bitrate = when (settings.bitrate) {
            "low" -> 4_000_000
            "medium" -> 8_000_000
            "high" -> 16_000_000
            else -> 8_000_000
        }

        val enableMic = settings.audioSource == "mic" || settings.audioSource == "both"

        val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra("PROJECTION_INTENT", data)
            putExtra("PROJECTION_RESULT_CODE", resultCode)
            putExtra("VIDEO_WIDTH", width)
            putExtra("VIDEO_HEIGHT", height)
            putExtra("VIDEO_FPS", settings.fps)
            putExtra("VIDEO_BITRATE", bitrate)
            putExtra("ENABLE_MIC", enableMic)
            putExtra("ENABLE_FACECAM", settings.cameraEnabled)
            putExtra("CAMERA_SHAPE", settings.cameraShape)
            putExtra("CAMERA_POS_X", settings.cameraPositionX)
            putExtra("CAMERA_POS_Y", settings.cameraPositionY)
            putExtra("CAMERA_SCALE", settings.cameraScale)
            putExtra("CAMERA_BORDER_WIDTH", settings.cameraBorderWidth)
            putExtra("CAMERA_BORDER_COLOR", settings.cameraBorderColor)
            putExtra("CAMERA_MIRRORED", settings.cameraMirrored)
        }

        if (settings.cameraEnabled) {
            com.screenpro.recording.FaceCamController.setFaceCamEnabled(true)
            val floatingIntent = Intent(this, FloatingBallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(floatingIntent)
            } else {
                startService(floatingIntent)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun performInstantScreenshot(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection: MediaProjection? = mediaProjectionManager?.getMediaProjection(resultCode, data)

        if (projection == null) {
            Toast.makeText(this, "Screenshot failed: Projection unavailable", Toast.LENGTH_SHORT).show()
            finishWithNoAnimation()
            return
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = projection.createVirtualDisplay(
            "ScreenPro_Single_Shot",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        val handler = Handler(Looper.getMainLooper())
        var captured = false

        imageReader.setOnImageAvailableListener({ reader ->
            if (captured) return@setOnImageAvailableListener
            captured = true
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
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
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)

                CoroutineScope(Dispatchers.IO).launch {
                    val mediaStoreRepository = MediaStoreRepository(applicationContext)
                    val uri = mediaStoreRepository.saveScreenshotToMediaStore(
                        cropped,
                        "ScreenPro_Screenshot_${System.currentTimeMillis()}"
                    )
                    handler.post {
                        Toast.makeText(
                            applicationContext,
                            if (uri != null) "Screenshot saved to Pictures/ScreenPro" else "Failed to save screenshot",
                            Toast.LENGTH_SHORT
                        ).show()
                        finishWithNoAnimation()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                finishWithNoAnimation()
            } finally {
                image.close()
                reader.close()
                virtualDisplay?.release()
                projection.stop()
            }
        }, handler)

        // Safety timeout in case no frame is delivered
        handler.postDelayed({
            if (!captured) {
                virtualDisplay?.release()
                projection.stop()
                imageReader.close()
                finishWithNoAnimation()
            }
        }, 1500)
    }

    private fun finishWithNoAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}

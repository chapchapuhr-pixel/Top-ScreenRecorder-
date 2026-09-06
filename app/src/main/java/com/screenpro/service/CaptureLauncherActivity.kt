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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenpro.ads.RewardAdManager
import com.screenpro.data.SettingsManager
import com.screenpro.recording.VideoResolutionHelper
import com.screenpro.storage.MediaStoreRepository
import com.screenpro.ui.components.CountdownOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Headless translucent Activity that handles MediaProjection permission prompts
 * for the Floating Ball directly over whatever app/game the user is currently using,
 * and presents a professional instant screenshot preview with Save to Phone reward ad.
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
                    val settingsManager = SettingsManager(applicationContext)
                    val settings = settingsManager.settings.value
                    if (settings.countdown > 0) {
                        setContent {
                            CountdownOverlay(
                                initialCount = settings.countdown,
                                onFinished = {
                                    startRecordingService(result.resultCode, result.data!!)
                                    finishWithNoAnimation()
                                },
                                onDismiss = {
                                    finishWithNoAnimation()
                                }
                            )
                        }
                    } else {
                        startRecordingService(result.resultCode, result.data!!)
                        finishWithNoAnimation()
                    }
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
        val bitrate = VideoResolutionHelper.calculateBitrate(settings)
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
            putExtra("AUDIO_BITRATE", settings.audioBitrate)
            putExtra("AUDIO_SAMPLE_RATE", settings.audioSampleRate)
            putExtra("AUDIO_CHANNELS", settings.audioChannels)
            putExtra("ENABLE_FACECAM", settings.cameraEnabled)
            putExtra("CAMERA_SHAPE", settings.cameraShape)
            putExtra("CAMERA_POS_X", settings.cameraPositionX)
            putExtra("CAMERA_POS_Y", settings.cameraPositionY)
            putExtra("CAMERA_SCALE", settings.cameraScale)
            putExtra("CAMERA_BORDER_WIDTH", settings.cameraBorderWidth)
            putExtra("CAMERA_BORDER_COLOR", settings.cameraBorderColor)
            putExtra("CAMERA_MIRRORED", settings.cameraMirrored)
            putExtra("SHOW_TOUCHES", settings.showTouches)
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

        val handler = Handler(Looper.getMainLooper())

        // Mandatory on Android 14+ (API 34+) before creating VirtualDisplay
        try {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {}
            }, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = try {
            projection.createVirtualDisplay(
                "ScreenPro_Single_Shot",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            projection.stop()
            imageReader.close()
            Toast.makeText(this, "Screenshot display error: ${e.message}", Toast.LENGTH_SHORT).show()
            finishWithNoAnimation()
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
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (cropped != bitmap) {
                    bitmap.recycle()
                }

                CoroutineScope(Dispatchers.IO).launch {
                    val mediaStoreRepository = MediaStoreRepository(applicationContext)
                    val title = "Screenshot_${System.currentTimeMillis()}"
                    val mediaItem = mediaStoreRepository.saveScreenshotToAppLibrary(cropped, title)
                    try {
                        mediaStoreRepository.saveScreenshotToMediaStore(cropped, title)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    handler.post {
                        showScreenshotResult(cropped, title, mediaItem)
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

        handler.postDelayed({
            if (!captured) {
                virtualDisplay?.release()
                projection.stop()
                imageReader.close()
                finishWithNoAnimation()
            }
        }, 2500)
    }

    private fun showScreenshotResult(
        bitmap: Bitmap,
        title: String,
        mediaItem: com.screenpro.data.model.MediaItem
    ) {
        setContent {
            var isSavedToGallery by remember { mutableStateOf(false) }
            var isSaving by remember { mutableStateOf(false) }

            // Auto dismiss after 7 seconds if untouched
            LaunchedEffect(Unit) {
                delay(7000)
                finishWithNoAnimation()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { finishWithNoAnimation() },
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = Color(0xFF161616),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C2C2C)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) {}
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .fillMaxWidth()
                    ) {
                        // Title header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(Color(0xFF1E88E5).copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF2979FF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Screenshot Captured!",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Saved in Free Screen Recorder Library",
                                        color = Color(0xFF9E9E9E),
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            IconButton(
                                onClick = { finishWithNoAnimation() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = Color.LightGray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Preview Thumbnail and action controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Thumbnail Preview
                            Box(
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(125.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black)
                                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(10.dp))
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Screenshot Preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Actions
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Save to Phone (Rewarded Ad)
                                if (isSavedToGallery) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF1B3B22), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Saved in Phone Gallery",
                                            color = Color(0xFF81C784),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            isSaving = true
                                            RewardAdManager.showRewardAd(
                                                activity = this@CaptureLauncherActivity,
                                                onRewardGranted = {
                                                    CoroutineScope(Dispatchers.IO).launch {
                                                        val repo = MediaStoreRepository(applicationContext)
                                                        repo.saveScreenshotToMediaStore(bitmap, title)
                                                        repo.saveScreenshotToPhoneGallery(mediaItem)
                                                        withContext(Dispatchers.Main) {
                                                            isSavedToGallery = true
                                                            isSaving = false
                                                            Toast.makeText(
                                                                applicationContext,
                                                                "Screenshot saved to Phone Gallery!",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                },
                                                onComplete = {
                                                    isSaving = false
                                                }
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            if (isSaving) "Saving..." else "Save to Phone",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                // Open in Library Button
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(applicationContext, com.screenpro.MainActivity::class.java).apply {
                                            putExtra("TARGET_SCREEN", "library")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        }
                                        startActivity(intent)
                                        finishWithNoAnimation()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF383838)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                ) {
                                    Icon(
                                        Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open in Library", fontSize = 12.sp)
                                }

                                // Share Button
                                OutlinedButton(
                                    onClick = {
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "image/png"
                                            putExtra(Intent.EXTRA_STREAM, mediaItem.uri)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        }
                                        startActivity(Intent.createChooser(sendIntent, "Share Screenshot").apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        })
                                        finishWithNoAnimation()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF383838)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
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

package com.screenpro.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.screenpro.MainActivity
import com.screenpro.R
import com.screenpro.data.SettingsManager
import com.screenpro.recording.RecordingController
import com.screenpro.recording.ScreenRecordingManager
import com.screenpro.recording.TouchVisualizerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ScreenRecordService
 * Handles Android 14+ FOREGROUND_SERVICE_MEDIA_PROJECTION compliance.
 * Starts foreground with proper notification before initializing VirtualDisplay.
 */
class ScreenRecordService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var recordingManager: ScreenRecordingManager
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "screenpro_recording_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.screenpro.ACTION_START"
        const val ACTION_PAUSE = "com.screenpro.ACTION_PAUSE"
        const val ACTION_RESUME = "com.screenpro.ACTION_RESUME"
        const val ACTION_STOP = "com.screenpro.ACTION_STOP"
        const val ACTION_SCREENSHOT = "com.screenpro.ACTION_SCREENSHOT"
        const val ACTION_UPDATE_FACECAM = "com.screenpro.ACTION_UPDATE_FACECAM"
        const val ACTION_TOGGLE_FACECAM = "com.screenpro.ACTION_TOGGLE_FACECAM"
        const val ACTION_HIDE_FACECAM = "com.screenpro.ACTION_HIDE_FACECAM"
        const val ACTION_SHOW_FACECAM = "com.screenpro.ACTION_SHOW_FACECAM"
        const val ACTION_HOLD_AND_PREVIEW = "com.screenpro.ACTION_HOLD_AND_PREVIEW"
        const val ACTION_CONTINUE = "com.screenpro.ACTION_CONTINUE"
        const val ACTION_SAVE_AND_FINISH = "com.screenpro.ACTION_SAVE_AND_FINISH"
        const val ACTION_DISCARD = "com.screenpro.ACTION_DISCARD"
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScreenRecordService = this@ScreenRecordService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        recordingManager = ScreenRecordingManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("PROJECTION_INTENT", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("PROJECTION_INTENT")
                }

                val resultCode = intent.getIntExtra("PROJECTION_RESULT_CODE", Activity.RESULT_OK)
                val width = intent.getIntExtra("VIDEO_WIDTH", 1080)
                val height = intent.getIntExtra("VIDEO_HEIGHT", 1920)
                val fps = intent.getIntExtra("VIDEO_FPS", 60)
                val bitrate = intent.getIntExtra("VIDEO_BITRATE", 8_000_000)
                val enableMic = intent.getBooleanExtra("ENABLE_MIC", true)
                val audioBitrate = intent.getIntExtra("AUDIO_BITRATE", 192_000)
                val audioSampleRate = intent.getIntExtra("AUDIO_SAMPLE_RATE", 48_000)
                val audioChannels = intent.getIntExtra("AUDIO_CHANNELS", 2)

                val enableFaceCam = intent.getBooleanExtra("ENABLE_FACECAM", false)
                val showTouches = intent.getBooleanExtra("SHOW_TOUCHES", false)
                if (showTouches) {
                    TouchVisualizerHelper.enableTouchesForRecording(this)
                }
                val cameraShape = intent.getStringExtra("CAMERA_SHAPE") ?: "circle"
                val cameraPosX = intent.getFloatExtra("CAMERA_POS_X", 0.75f)
                val cameraPosY = intent.getFloatExtra("CAMERA_POS_Y", 0.08f)
                val cameraScale = intent.getFloatExtra("CAMERA_SCALE", 0.26f)
                val cameraBorderWidth = intent.getIntExtra("CAMERA_BORDER_WIDTH", 3)
                val cameraBorderColor = intent.getStringExtra("CAMERA_BORDER_COLOR") ?: "#FF4B2B"
                val cameraMirrored = intent.getBooleanExtra("CAMERA_MIRRORED", true)

                startRecordingForeground(
                    projectionData = projectionData,
                    resultCode = resultCode,
                    width = width,
                    height = height,
                    fps = fps,
                    bitrate = bitrate,
                    enableMic = enableMic,
                    enableFaceCam = enableFaceCam,
                    cameraShape = cameraShape,
                    cameraPosX = cameraPosX,
                    cameraPosY = cameraPosY,
                    cameraScale = cameraScale,
                    cameraBorderWidth = cameraBorderWidth,
                    cameraBorderColor = cameraBorderColor,
                    cameraMirrored = cameraMirrored,
                    audioBitrate = audioBitrate,
                    audioSampleRate = audioSampleRate,
                    audioChannels = audioChannels
                )
            }
            ACTION_HOLD_AND_PREVIEW -> {
                recordingManager.holdAndPreviewSegment { file, uri ->
                    updateNotification(isPaused = true, RecordingController.elapsedSeconds.value)
                }
            }
            ACTION_CONTINUE -> {
                recordingManager.continueRecordingSegment()
                updateNotification(isPaused = false, RecordingController.elapsedSeconds.value)
            }
            ACTION_SAVE_AND_FINISH -> stopRecordingForeground()
            ACTION_DISCARD -> {
                tearDownShakeDetector()
                recordingManager.discardRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_FACECAM -> {
                val posX = intent.getFloatExtra("CAMERA_POS_X", -1f)
                val posY = intent.getFloatExtra("CAMERA_POS_Y", -1f)
                if (posX >= 0f && posY >= 0f) {
                    recordingManager.updateFaceCamPosition(posX, posY)
                }
            }
            ACTION_TOGGLE_FACECAM -> {
                com.screenpro.recording.FaceCamController.toggleHideShow()
                updateNotification(RecordingController.isPaused.value, RecordingController.elapsedSeconds.value)
            }
            ACTION_HIDE_FACECAM -> {
                com.screenpro.recording.FaceCamController.hideFaceCam()
                updateNotification(RecordingController.isPaused.value, RecordingController.elapsedSeconds.value)
            }
            ACTION_SHOW_FACECAM -> {
                com.screenpro.recording.FaceCamController.showFaceCam()
                updateNotification(RecordingController.isPaused.value, RecordingController.elapsedSeconds.value)
            }
            ACTION_PAUSE -> {
                recordingManager.pauseRecording()
                RecordingController.onRecordingPaused()
            }
            ACTION_RESUME -> {
                recordingManager.resumeRecording()
                RecordingController.onRecordingResumed()
            }
            ACTION_STOP -> stopRecordingForeground()
            ACTION_SCREENSHOT -> recordingManager.takeScreenshot()
        }
        return START_NOT_STICKY
    }

    private fun startRecordingForeground(
        projectionData: Intent?,
        resultCode: Int,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        enableMic: Boolean,
        enableFaceCam: Boolean = false,
        cameraShape: String = "circle",
        cameraPosX: Float = 0.75f,
        cameraPosY: Float = 0.08f,
        cameraScale: Float = 0.26f,
        cameraBorderWidth: Int = 3,
        cameraBorderColor: String = "#FF4B2B",
        cameraMirrored: Boolean = true,
        audioBitrate: Int = 192_000,
        audioSampleRate: Int = 48_000,
        audioChannels: Int = 2
    ) {
        val notification = buildNotification(isPaused = false, elapsedSec = 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && enableMic) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && enableFaceCam) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (projectionData != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, projectionData)
            recordingManager.setMediaProjection(projection)
            recordingManager.startRecording(
                width = width,
                height = height,
                fps = fps,
                bitrate = bitrate,
                enableMic = enableMic,
                enableFaceCam = enableFaceCam,
                cameraShape = cameraShape,
                cameraPositionX = cameraPosX,
                cameraPositionY = cameraPosY,
                cameraScale = cameraScale,
                cameraBorderWidth = cameraBorderWidth,
                cameraBorderColor = cameraBorderColor,
                cameraMirrored = cameraMirrored,
                audioBitrate = audioBitrate,
                audioSampleRate = audioSampleRate,
                audioChannels = audioChannels
            )
            RecordingController.onRecordingStarted()
        }

        serviceScope.launch {
            RecordingController.elapsedSeconds.collectLatest { sec ->
                updateNotification(RecordingController.isPaused.value, sec)
            }
        }

        serviceScope.launch {
            com.screenpro.recording.FaceCamController.isFaceCamHidden.collectLatest {
                updateNotification(RecordingController.isPaused.value, RecordingController.elapsedSeconds.value)
            }
        }

        serviceScope.launch {
            com.screenpro.recording.FaceCamController.isFaceCamEnabled.collectLatest {
                updateNotification(RecordingController.isPaused.value, RecordingController.elapsedSeconds.value)
            }
        }

        val settings = SettingsManager(applicationContext).settings.value
        if (settings.shakeToStop) {
            setupShakeDetector()
        }
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var shakeListener: SensorEventListener? = null
    private var lastShakeTimestamp = 0L

    private fun setupShakeDetector() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            shakeListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event == null) return
                    val x = event.values[0] / SensorManager.GRAVITY_EARTH
                    val y = event.values[1] / SensorManager.GRAVITY_EARTH
                    val z = event.values[2] / SensorManager.GRAVITY_EARTH
                    val gForce = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                    if (gForce > 2.8f) {
                        val now = System.currentTimeMillis()
                        if (now - lastShakeTimestamp > 2500) {
                            lastShakeTimestamp = now
                            stopRecordingForeground()
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            accelerometer?.let {
                sensorManager?.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun tearDownShakeDetector() {
        shakeListener?.let {
            sensorManager?.unregisterListener(it)
        }
        shakeListener = null
        sensorManager = null
    }

    private fun stopRecordingForeground() {
        // Immediately dismiss facecam overlay when recording finishes
        com.screenpro.recording.FaceCamController.setFaceCamEnabled(false)
        try {
            val sm = com.screenpro.data.SettingsManager(applicationContext)
            sm.updateSettings(sm.settings.value.copy(cameraEnabled = false))
        } catch (_: Exception) {}

        tearDownShakeDetector()
        TouchVisualizerHelper.restoreTouchesAfterRecording(this)
        recordingManager.stopRecording { success, uri ->
            RecordingController.onRecordingStopped()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live recording progress and quick controls"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isPaused: Boolean, elapsedSec: Long): Notification {
        val openIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val pauseResumeIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }.let {
            PendingIntent.getService(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }.let {
            PendingIntent.getService(this, 2, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val screenshotIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_SCREENSHOT
        }.let {
            PendingIntent.getService(this, 3, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val mins = elapsedSec / 60
        val secs = elapsedSec % 60
        val timeStr = String.format("%02d:%02d", mins, secs)

        val isFaceCamActive = com.screenpro.recording.FaceCamController.isFaceCamEnabled.value
        val isFaceCamHidden = com.screenpro.recording.FaceCamController.isFaceCamHidden.value

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Free Screen Recording")
            .setContentText(if (isPaused) "Paused • $timeStr (Shake phone to stop)" else "Recording • $timeStr (Shake phone to stop)")
            .setSmallIcon(R.drawable.ic_notification_record)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                if (isPaused) R.drawable.ic_play else R.drawable.ic_pause,
                if (isPaused) "Resume" else "Pause",
                pauseResumeIntent
            )
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)

        if (isFaceCamActive) {
            val faceCamToggleIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = ACTION_TOGGLE_FACECAM
            }.let {
                PendingIntent.getService(this, 4, it, PendingIntent.FLAG_IMMUTABLE)
            }
            builder.addAction(
                R.drawable.ic_camera,
                if (isFaceCamHidden) "Show FaceCam" else "Hide FaceCam",
                faceCamToggleIntent
            )
        } else {
            builder.addAction(R.drawable.ic_camera, "Snapshot", screenshotIntent)
        }

        return builder.build()
    }

    private fun updateNotification(isPaused: Boolean, elapsedSec: Long) {
        val notification = buildNotification(isPaused, elapsedSec)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        TouchVisualizerHelper.restoreTouchesAfterRecording(this)
        serviceScope.cancel()
    }
}

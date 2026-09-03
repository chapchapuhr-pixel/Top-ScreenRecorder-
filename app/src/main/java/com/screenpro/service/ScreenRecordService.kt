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
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.screenpro.MainActivity
import com.screenpro.R
import com.screenpro.recording.RecordingController
import com.screenpro.recording.ScreenRecordingManager
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

                startRecordingForeground(projectionData, resultCode, width, height, fps, bitrate, enableMic)
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
        enableMic: Boolean
    ) {
        val notification = buildNotification(isPaused = false, elapsedSec = 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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
                enableMic = enableMic
            )
            RecordingController.onRecordingStarted()
        }

        serviceScope.launch {
            RecordingController.elapsedSeconds.collectLatest { sec ->
                updateNotification(RecordingController.isPaused.value, sec)
            }
        }
    }

    private fun stopRecordingForeground() {
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

        val mins = elapsedSec / 60
        val secs = elapsedSec % 60
        val timeStr = String.format("%02d:%02d", mins, secs)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenPro Recording")
            .setContentText(if (isPaused) "Recording Paused • $timeStr" else "Recording in Progress • $timeStr")
            .setSmallIcon(R.drawable.ic_notification_record)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                if (isPaused) R.drawable.ic_play else R.drawable.ic_pause,
                if (isPaused) "Resume" else "Pause",
                pauseResumeIntent
            )
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(isPaused: Boolean, elapsedSec: Long) {
        val notification = buildNotification(isPaused, elapsedSec)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

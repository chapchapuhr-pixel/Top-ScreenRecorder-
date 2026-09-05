package com.screenpro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.*
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import com.screenpro.MainActivity
import com.screenpro.R
import com.screenpro.data.SettingsManager
import com.screenpro.recording.FaceCamController
import com.screenpro.recording.RecordingController
import com.screenpro.ui.components.FloatingFaceCamOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

/**
 * Foreground Service that maintains an X-Recorder style floating ball
 * over any other app even when ScreenPro is closed/minimized.
 */
class FloatingBallService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var settingsManager: SettingsManager
    private var overlayComposeView: ComposeView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var isCurrentlyHiddenForRecording = false

    // System-wide Floating FaceCam overlay views
    private var faceCamComposeView: ComposeView? = null
    private var faceCamLayoutParams: WindowManager.LayoutParams? = null
    private var faceCamPosX = 800
    private var faceCamPosY = 200

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var lifecycleOwner: ServiceLifecycleOwner? = null

    // Window / Screen dimensions
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var ballSizePx = 160 // default ~56dp

    // Coordinates of collapsed ball
    private var ballPosX = 800
    private var ballPosY = 600

    private val isMenuExpanded = mutableStateOf(false)

    companion object {
        const val CHANNEL_ID = "screenpro_floating_ball_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START = "com.screenpro.FLOATING_START"
        const val ACTION_STOP = "com.screenpro.FLOATING_STOP"
        const val ACTION_TOGGLE = "com.screenpro.FLOATING_TOGGLE"

        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsManager = SettingsManager(applicationContext)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        lifecycleOwner = ServiceLifecycleOwner().apply { onCreate() }
        calculateScreenSize()
        initOverlayWindow()

        serviceScope.launch {
            settingsManager.settings.collectLatest { settings ->
                syncFaceCamOverlay(settings)
            }
        }

        serviceScope.launch {
            FaceCamController.isFaceCamEnabled.collectLatest { enabled ->
                val current = settingsManager.settings.value
                if (current.cameraEnabled != enabled) {
                    settingsManager.updateSettings(current.copy(cameraEnabled = enabled))
                }
                syncFaceCamOverlay(settingsManager.settings.value)
            }
        }

        serviceScope.launch {
            FaceCamController.isFaceCamHidden.collectLatest {
                syncFaceCamOverlay(settingsManager.settings.value)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                isMenuExpanded.value = !isMenuExpanded.value
                updateWindowForMenuState(isMenuExpanded.value)
            }
        }
        return START_STICKY
    }

    private fun calculateScreenSize() {
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        screenWidth = size.x
        screenHeight = size.y

        val density = resources.displayMetrics.density
        ballSizePx = (56 * density).roundToInt()

        // Default position: near right edge, upper third
        ballPosX = screenWidth - ballSizePx - (16 * density).roundToInt()
        ballPosY = (screenHeight * 0.35f).roundToInt()
    }

    private fun initOverlayWindow() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            ballSizePx,
            ballSizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballPosX
            y = ballPosY
        }
        windowLayoutParams = params

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                val isRecording by RecordingController.isRecording.collectAsState()
                val isPaused by RecordingController.isPaused.collectAsState()
                val elapsedSeconds by RecordingController.elapsedSeconds.collectAsState()
                val isFloatingPreviewVisible by RecordingController.isFloatingPreviewVisible.collectAsState()
                val previewSegmentFile by RecordingController.latestRecordedSegmentFile.collectAsState()
                val previewSegmentUri by RecordingController.latestRecordedSegmentUri.collectAsState()
                val settings by settingsManager.settings.collectAsState()
                val expanded by isMenuExpanded

                FloatingOverlayContent(
                    isExpanded = expanded,
                    isRecording = isRecording,
                    isPaused = isPaused,
                    isFloatingPreviewVisible = isFloatingPreviewVisible,
                    previewSegmentFile = previewSegmentFile,
                    previewSegmentUri = previewSegmentUri,
                    hideWhileRecording = settings.hideFloatingBallDuringRecording,
                    durationSeconds = elapsedSeconds,
                    isFaceCamActive = settings.cameraEnabled,
                    ballSizePx = ballSizePx,
                    screenWidthPx = screenWidth,
                    screenHeightPx = screenHeight,
                    currentX = ballPosX,
                    currentY = ballPosY,
                    onBallTapped = {
                        if (isRecording) {
                            // Immediately stop active recording segment and show Floating Preview with Save & Continue
                            val holdIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                                action = ScreenRecordService.ACTION_HOLD_AND_PREVIEW
                            }
                            startService(holdIntent)
                            RecordingController.showFloatingPreview()
                            updateWindowForMenuState(true)
                        } else {
                            isMenuExpanded.value = true
                            updateWindowForMenuState(true)
                        }
                    },
                    onMenuClosed = {
                        isMenuExpanded.value = false
                        updateWindowForMenuState(false)
                    },
                    onImmediateStopAndPreview = {
                        val holdIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_HOLD_AND_PREVIEW
                        }
                        startService(holdIntent)
                        RecordingController.showFloatingPreview()
                        updateWindowForMenuState(true)
                    },
                    onContinueRecording = {
                        RecordingController.hideFloatingPreview()
                        val continueIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_CONTINUE
                        }
                        startService(continueIntent)
                        updateWindowForMenuState(false)
                    },
                    onSaveRecording = {
                        RecordingController.hideFloatingPreview()
                        val saveIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_SAVE_AND_FINISH
                        }
                        startService(saveIntent)
                        updateWindowForMenuState(false)
                        Toast.makeText(applicationContext, "Video saved to gallery!", Toast.LENGTH_SHORT).show()
                    },
                    onEditRecording = {
                        RecordingController.hideFloatingPreview()
                        val saveIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_SAVE_AND_FINISH
                        }
                        startService(saveIntent)
                        updateWindowForMenuState(false)
                        val editorIntent = Intent(applicationContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("TARGET_SCREEN", "editor")
                        }
                        startActivity(editorIntent)
                    },
                    onDiscardRecording = {
                        RecordingController.hideFloatingPreview()
                        val discardIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_DISCARD
                        }
                        startService(discardIntent)
                        updateWindowForMenuState(false)
                    },
                    onClosePreview = {
                        RecordingController.hideFloatingPreview()
                        updateWindowForMenuState(false)
                    },
                    onPositionChanged = { newX, newY ->
                        ballPosX = newX
                        ballPosY = newY
                        if (!isMenuExpanded.value && !RecordingController.isFloatingPreviewVisible.value) {
                            windowLayoutParams?.let { lp ->
                                lp.x = newX
                                lp.y = newY
                                windowManager.updateViewLayout(this@apply, lp)
                            }
                        }
                    },
                    onDismissBall = {
                        settingsManager.updateSettings(settings.copy(floatingBallEnabled = false))
                        stopSelf()
                    },
                    onStartRecord = {
                        isMenuExpanded.value = false
                        updateWindowForMenuState(false)
                        CaptureLauncherActivity.startRecord(applicationContext)
                    },
                    onPauseRecord = {
                        val pauseIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_PAUSE
                        }
                        startService(pauseIntent)
                    },
                    onResumeRecord = {
                        val resumeIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_RESUME
                        }
                        startService(resumeIntent)
                    },
                    onStopRecord = {
                        isMenuExpanded.value = false
                        updateWindowForMenuState(false)
                        val stopIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_STOP
                        }
                        startService(stopIntent)
                    },
                    onTakeScreenshot = {
                        isMenuExpanded.value = false
                        updateWindowForMenuState(false)
                        if (RecordingController.isRecording.value) {
                            val screenIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                                action = ScreenRecordService.ACTION_SCREENSHOT
                            }
                            startService(screenIntent)
                        } else {
                            CaptureLauncherActivity.captureScreenshot(applicationContext)
                        }
                    },
                    onToggleFaceCam = {
                        val newCamState = !settings.cameraEnabled
                        if (newCamState) {
                            val isPermitted = ContextCompat.checkSelfPermission(
                                applicationContext,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!isPermitted) {
                                isMenuExpanded.value = false
                                updateWindowForMenuState(false)
                                CaptureLauncherActivity.requestCameraPermission(applicationContext)
                            } else {
                                settingsManager.updateSettings(settings.copy(cameraEnabled = true))
                                FaceCamController.setFaceCamEnabled(true)
                                Toast.makeText(applicationContext, "FaceCam activated", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            settingsManager.updateSettings(settings.copy(cameraEnabled = false))
                            FaceCamController.setFaceCamEnabled(false)
                        }
                    },
                    onOpenHome = {
                        isMenuExpanded.value = false
                        updateWindowForMenuState(false)
                        val homeIntent = Intent(applicationContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("TARGET_SCREEN", "home")
                        }
                        startActivity(homeIntent)
                    },
                    onOpenSettings = {
                        isMenuExpanded.value = false
                        updateWindowForMenuState(false)
                        val settingsIntent = Intent(applicationContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("TARGET_SCREEN", "settings")
                        }
                        startActivity(settingsIntent)
                    }
                )
            }
        }

        overlayComposeView = composeView
        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }

        // Monitor recording state: if recording is active, no preview is open, and hideFloatingBallDuringRecording is enabled,
        // hide the entire overlay window from the screen so it is completely excluded from video capture.
        serviceScope.launch {
            combine(
                RecordingController.isRecording,
                RecordingController.isPaused,
                RecordingController.isFloatingPreviewVisible,
                settingsManager.settings
            ) { recording, paused, previewVisible, settings ->
                val shouldHide = recording && !paused && !previewVisible && settings.hideFloatingBallDuringRecording
                shouldHide to previewVisible
            }.collectLatest { (shouldHide, previewVisible) ->
                withContext(Dispatchers.Main) {
                    isCurrentlyHiddenForRecording = shouldHide
                    updateWindowForMenuState(isMenuExpanded.value || previewVisible)
                }
            }
        }
    }

    private fun updateWindowForMenuState(expandedOrPreview: Boolean) {
        val view = overlayComposeView ?: return
        val params = windowLayoutParams ?: return

        if (isCurrentlyHiddenForRecording) {
            view.visibility = View.GONE
            params.width = 0
            params.height = 0
        } else if (expandedOrPreview) {
            view.visibility = View.VISIBLE
            // Expand window to fullscreen so backdrop and menu or preview render properly over everything
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
        } else {
            view.visibility = View.VISIBLE
            // Shrink window to just the small ball so background apps get touches uninterrupted
            params.width = ballSizePx
            params.height = ballSizePx
            params.x = ballPosX
            params.y = ballPosY
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ScreenPro Floating Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating quick-access ball active across all apps"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hideIntent = Intent(this, FloatingBallService::class.java).apply {
            action = ACTION_STOP
        }
        val hidePendingIntent = PendingIntent.getService(
            this,
            1,
            hideIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenPro Floating Ball")
            .setContentText("Quick tools active • Tap to open app")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide Ball", hidePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getFaceCamDimensions(settings: com.screenpro.data.model.AppSettings, isCollapsed: Boolean): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        if (isCollapsed) {
            return (110 * density).roundToInt() to (40 * density).roundToInt()
        }
        return when (settings.cameraSize) {
            "small" -> when (settings.cameraShape) {
                "rectangle" -> (130 * density).roundToInt() to (98 * density).roundToInt()
                else -> (110 * density).roundToInt() to (110 * density).roundToInt()
            }
            "large" -> when (settings.cameraShape) {
                "rectangle" -> (200 * density).roundToInt() to (150 * density).roundToInt()
                else -> (175 * density).roundToInt() to (175 * density).roundToInt()
            }
            else -> when (settings.cameraShape) { // medium
                "rectangle" -> (165 * density).roundToInt() to (124 * density).roundToInt()
                else -> (140 * density).roundToInt() to (140 * density).roundToInt()
            }
        }
    }

    private fun syncFaceCamOverlay(settings: com.screenpro.data.model.AppSettings) {
        if (!Settings.canDrawOverlays(this)) return

        val isCameraPermitted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val shouldShowFaceCam = settings.cameraEnabled && isCameraPermitted

        if (shouldShowFaceCam) {
            if (faceCamComposeView == null) {
                createFaceCamOverlay(settings)
            } else {
                updateFaceCamLayoutDimensions(settings)
            }
        } else {
            removeFaceCamOverlay()
        }
    }

    private fun createFaceCamOverlay(settings: com.screenpro.data.model.AppSettings) {
        if (faceCamComposeView != null) return

        val isCollapsed = FaceCamController.isFaceCamHidden.value
        val (widthPx, heightPx) = getFaceCamDimensions(settings, isCollapsed)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        if (faceCamPosX == 800 && faceCamPosY == 200) {
            faceCamPosX = (settings.cameraPositionX * (screenWidth - widthPx)).roundToInt().coerceIn(0, (screenWidth - widthPx).coerceAtLeast(0))
            faceCamPosY = (settings.cameraPositionY * (screenHeight - heightPx)).roundToInt().coerceIn(0, (screenHeight - heightPx).coerceAtLeast(0))
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = faceCamPosX
            y = faceCamPosY
        }
        faceCamLayoutParams = params

        val owner = lifecycleOwner ?: return

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)

            setContent {
                val currentSettings by settingsManager.settings.collectAsState()
                val isFaceCamHidden by FaceCamController.isFaceCamHidden.collectAsState()
                val isFrontCam by FaceCamController.isFrontCamera.collectAsState()

                FloatingFaceCamOverlay(
                    isCollapsed = isFaceCamHidden,
                    shapeType = currentSettings.cameraShape,
                    sizeType = currentSettings.cameraSize,
                    borderWidthDp = currentSettings.cameraBorderWidth,
                    borderColorHex = currentSettings.cameraBorderColor,
                    isMirrored = currentSettings.cameraMirrored,
                    isFrontCamera = isFrontCam,
                    onDrag = { dx, dy ->
                        val (curW, curH) = getFaceCamDimensions(currentSettings, isFaceCamHidden)
                        faceCamPosX = (faceCamPosX + dx).roundToInt().coerceIn(0, (screenWidth - curW).coerceAtLeast(0))
                        faceCamPosY = (faceCamPosY + dy).roundToInt().coerceIn(40, (screenHeight - curH - 40).coerceAtLeast(40))
                        faceCamLayoutParams?.let { lp ->
                            lp.x = faceCamPosX
                            lp.y = faceCamPosY
                            try {
                                windowManager.updateViewLayout(this@apply, lp)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        val pctX = (faceCamPosX.toFloat() / (screenWidth - curW).coerceAtLeast(1)).coerceIn(0f, 1f)
                        val pctY = (faceCamPosY.toFloat() / (screenHeight - curH).coerceAtLeast(1)).coerceIn(0f, 1f)
                        settingsManager.updateSettings(currentSettings.copy(cameraPositionX = pctX, cameraPositionY = pctY))
                    },
                    onToggleCollapse = { collapse ->
                        if (collapse) {
                            FaceCamController.hideFaceCam()
                        } else {
                            FaceCamController.showFaceCam()
                        }
                    },
                    onSwitchLens = { isFront ->
                        FaceCamController.setCameraLens(isFront)
                    },
                    onShapeChanged = { shape ->
                        settingsManager.updateSettings(currentSettings.copy(cameraShape = shape))
                    },
                    onSizeChanged = { size, scale ->
                        settingsManager.updateSettings(currentSettings.copy(cameraSize = size, cameraScale = scale))
                    },
                    onMirrorToggled = { mirrored ->
                        settingsManager.updateSettings(currentSettings.copy(cameraMirrored = mirrored))
                    },
                    onClose = {
                        FaceCamController.setFaceCamEnabled(false)
                        settingsManager.updateSettings(currentSettings.copy(cameraEnabled = false))
                    }
                )
            }
        }

        try {
            windowManager.addView(view, params)
            faceCamComposeView = view
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateFaceCamLayoutDimensions(settings: com.screenpro.data.model.AppSettings) {
        val view = faceCamComposeView ?: return
        val params = faceCamLayoutParams ?: return
        val isCollapsed = FaceCamController.isFaceCamHidden.value
        val (widthPx, heightPx) = getFaceCamDimensions(settings, isCollapsed)

        params.width = widthPx
        params.height = heightPx
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeFaceCamOverlay() {
        faceCamComposeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        faceCamComposeView = null
        faceCamLayoutParams = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        removeFaceCamOverlay()
        lifecycleOwner?.onDestroy()
        overlayComposeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayComposeView = null
    }
}

/**
 * Composable rendered directly inside the WindowManager overlay view.
 */
@Composable
private fun FloatingOverlayContent(
    isExpanded: Boolean,
    isRecording: Boolean,
    isPaused: Boolean,
    isFloatingPreviewVisible: Boolean,
    previewSegmentFile: File?,
    previewSegmentUri: Uri?,
    hideWhileRecording: Boolean,
    durationSeconds: Long,
    isFaceCamActive: Boolean,
    ballSizePx: Int,
    screenWidthPx: Int,
    screenHeightPx: Int,
    currentX: Int,
    currentY: Int,
    onBallTapped: () -> Unit,
    onMenuClosed: () -> Unit,
    onImmediateStopAndPreview: () -> Unit,
    onContinueRecording: () -> Unit,
    onSaveRecording: () -> Unit,
    onEditRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
    onClosePreview: () -> Unit,
    onPositionChanged: (Int, Int) -> Unit,
    onDismissBall: () -> Unit,
    onStartRecord: () -> Unit,
    onPauseRecord: () -> Unit,
    onResumeRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onToggleFaceCam: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var posX by remember { mutableStateOf(currentX.toFloat()) }
    var posY by remember { mutableStateOf(currentY.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    var isIdle by remember { mutableStateOf(false) }

    val dismissThresholdY = screenHeightPx - 260f
    val isOverDismissArea = isDragging && posY > dismissThresholdY

    LaunchedEffect(isDragging, isExpanded, isRecording, isFloatingPreviewVisible) {
        if (!isDragging && !isExpanded && !isFloatingPreviewVisible) {
            isIdle = false
            delay(3500)
            isIdle = true
        } else {
            isIdle = false
        }
    }

    fun formatDuration(seconds: Long): String {
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Floating Recorded Video Preview Dialog
        if (isFloatingPreviewVisible) {
            FloatingRecordedVideoPreview(
                videoFile = previewSegmentFile,
                videoUri = previewSegmentUri,
                durationSeconds = durationSeconds,
                onContinue = onContinueRecording,
                onSave = onSaveRecording,
                onEdit = onEditRecording,
                onDiscard = onDiscardRecording,
                onClose = onClosePreview
            )
        }
        // When expanded, show full dark touch-backdrop to close menu
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onMenuClosed()
                    }
            )

            // Quick Menu Card
            val menuWidth = 300f
            val menuHeight = 270f
            val menuX = (posX + ballSizePx / 2f - menuWidth / 2f)
                .coerceIn(24f, screenWidthPx - menuWidth - 24f)
            val menuY = (posY + ballSizePx / 2f - menuHeight / 2f)
                .coerceIn(80f, screenHeightPx - menuHeight - 80f)

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF161616),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C2C2C)),
                shadowElevation = 20.dp,
                modifier = Modifier
                    .offset { IntOffset(menuX.roundToInt(), menuY.roundToInt()) }
                    .width(300.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isRecording) {
                                            if (isPaused) Color(0xFFFFB300) else Color(0xFFFF1744)
                                        } else Color(0xFF00E676)
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRecording) {
                                    if (isPaused) "Paused (${formatDuration(durationSeconds)})"
                                    else "Recording (${formatDuration(durationSeconds)})"
                                } else "Ready to Record",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = onMenuClosed,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close Menu",
                                tint = Color.LightGray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Main recording buttons
                    if (isRecording) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (isPaused) onResumeRecord() else onPauseRecord()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPaused) Color(0xFF2E7D32) else Color(0xFF37474F)
                                )
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPaused) "Resume" else "Pause", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = onStopRecord,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Stop", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Button(
                            onClick = onStartRecord,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B2B))
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Recording", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = Color(0xFF2E2E2E))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Secondary Tools
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SystemToolItem(
                            icon = Icons.Default.CameraAlt,
                            label = "Capture",
                            isActive = false,
                            activeColor = Color(0xFF00E5FF),
                            onClick = onTakeScreenshot
                        )

                        SystemToolItem(
                            icon = Icons.Default.AccountCircle,
                            label = "Facecam",
                            isActive = isFaceCamActive,
                            activeColor = Color(0xFFFF4B2B),
                            onClick = onToggleFaceCam
                        )

                        SystemToolItem(
                            icon = Icons.Default.Home,
                            label = "ScreenPro",
                            isActive = false,
                            activeColor = Color.White,
                            onClick = onOpenHome
                        )

                        SystemToolItem(
                            icon = Icons.Default.Settings,
                            label = "Settings",
                            isActive = false,
                            activeColor = Color.White,
                            onClick = onOpenSettings
                        )

                        SystemToolItem(
                            icon = Icons.Default.VisibilityOff,
                            label = "Hide",
                            isActive = false,
                            activeColor = Color(0xFFFF5252),
                            onClick = onDismissBall
                        )
                    }
                }
            }
        }

        // Dismiss area when dragging
        if (isDragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(if (isOverDismissArea) 72.dp else 60.dp)
                    .clip(CircleShape)
                    .background(if (isOverDismissArea) Color(0xFFFF1744) else Color(0xCC212121))
                    .border(2.dp, if (isOverDismissArea) Color.White else Color(0xFF616161), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Hide Floating Ball",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    if (isOverDismissArea) {
                        Text("Release", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // The Floating Ball View itself
        // When expanded, hide the floating button completely and keep ONLY the Ready to Record panel.
        // When user clicks 'x' (or closes it), the menu closes and the floating button returns.
        // During active recording, hide the floating ball completely so it is never recorded into video.
        val shouldHideBallForRecording = isRecording && !isPaused && hideWhileRecording
        if (!isExpanded && !shouldHideBallForRecording) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .size(56.dp)
                    .alpha(if (isIdle) 0.55f else 0.95f)
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = if (isRecording) {
                                if (isPaused) listOf(Color(0xFFFFB300), Color(0xFFE65100))
                                else listOf(Color(0xFFFF5252), Color(0xFFD50000))
                            } else {
                                listOf(Color(0xFF2A2A2A), Color(0xFF141414))
                            }
                        )
                    )
                    .border(
                        2.dp,
                        if (isRecording) {
                            if (isPaused) Color(0xFFFFD54F) else Color(0xFFFF8A80)
                        } else Color(0xFFFF4B2B),
                        CircleShape
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                if (isRecording) {
                                    onImmediateStopAndPreview()
                                } else {
                                    onBallTapped()
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                isIdle = false
                            },
                            onDragEnd = {
                                isDragging = false
                                if (isOverDismissArea) {
                                    onDismissBall()
                                } else {
                                    // Snap gently toward nearest left or right edge
                                    val margin = 16f
                                    val targetSnapX = if (posX + ballSizePx / 2f < screenWidthPx / 2f) {
                                        margin
                                    } else {
                                        (screenWidthPx - ballSizePx - margin)
                                    }
                                    posX = targetSnapX
                                    onPositionChanged(posX.roundToInt(), posY.roundToInt())
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                isIdle = false
                                posX = (posX + dragAmount.x).coerceIn(8f, screenWidthPx - ballSizePx - 8f)
                                posY = (posY + dragAmount.y).coerceIn(40f, screenHeightPx - ballSizePx - 40f)
                                onPositionChanged(posX.roundToInt(), posY.roundToInt())
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isRecording) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // User request: Change floating camera button to ■ small pause
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop Recording and Preview",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = "Small pause indicator",
                                    tint = Color(0xFFFFD54F),
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                            Text(
                                text = formatDuration(durationSeconds),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Quick Recorder Menu",
                        tint = Color(0xFFFF4B2B),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

/**
 * Floating Recorded Video Preview Dialog
 * Displays the newly finished segment with an ExoPlayer preview,
 * giving the user direct buttons to "Continue" recording or "Save" the video.
 */
@Composable
private fun FloatingRecordedVideoPreview(
    videoFile: File?,
    videoUri: Uri?,
    durationSeconds: Long,
    onContinue: () -> Unit,
    onSave: () -> Unit,
    onEdit: () -> Unit,
    onDiscard: () -> Unit,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }

    val exoPlayer = remember(videoFile, videoUri) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            val mediaItem = when {
                videoFile != null && videoFile.exists() -> MediaItem.fromUri(Uri.fromFile(videoFile))
                videoUri != null -> MediaItem.fromUri(videoUri)
                else -> null
            }
            if (mediaItem != null) {
                setMediaItem(mediaItem)
                prepare()
            }
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    // Fullscreen Scrim overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* prevent touch passthrough */ },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF181818)),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF333333)),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF4B2B))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Recorded Segment",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2A2A2A)
                    ) {
                        Text(
                            text = String.format("%02d:%02d", (durationSeconds % 3600) / 60, durationSeconds % 60),
                            color = Color(0xFFFF8A80),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Video Player Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (videoFile != null && videoFile.exists() || videoUri != null) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = null,
                                tint = Color(0xFFFF4B2B),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Segment Ready",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Play/Pause tap overlay
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                exoPlayer.pause()
                                isPlaying = false
                            } else {
                                exoPlayer.play()
                                isPlaying = true
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Toggle playback",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary Actions: Save and Continue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Continue Button
                    Button(
                        onClick = onContinue,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Continue", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    // Save Button
                    Button(
                        onClick = onSave,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B2B)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Video", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Secondary Actions: Edit Crop / Discard
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onEdit,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3E3E3E)),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Default.Crop, contentDescription = null, tint = Color(0xFFFF4B2B), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit & Crop", color = Color.White, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onDiscard,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3E3E3E)),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Discard", color = Color(0xFFFF5252), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemToolItem(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isActive) activeColor.copy(alpha = 0.22f) else Color(0xFF242424))
                .border(1.dp, if (isActive) activeColor else Color(0xFF3A3A3A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isActive) activeColor else Color.LightGray,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Custom LifecycleOwner, ViewModelStoreOwner, and SavedStateRegistryOwner for ComposeView in a Service
 */
class ServiceLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore: ViewModelStore = store
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

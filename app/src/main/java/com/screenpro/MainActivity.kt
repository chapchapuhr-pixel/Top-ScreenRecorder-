package com.screenpro

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.screenpro.ads.AppOpenManager
import com.screenpro.ads.RewardAdManager
import com.screenpro.data.SettingsManager
import com.screenpro.data.model.MediaItem
import com.screenpro.data.model.MediaType
import com.screenpro.recording.RecordingController
import com.screenpro.recording.VideoResolutionHelper
import com.screenpro.service.CaptureLauncherActivity
import com.screenpro.service.FloatingBallService
import com.screenpro.service.ScreenRecordService
import com.screenpro.storage.MediaStoreRepository
import com.screenpro.ui.components.CountdownOverlay
import com.screenpro.ui.components.DrawingOverlay
import com.screenpro.ui.components.FaceCamBubble
import com.screenpro.ui.components.FloatingControlBall
import com.screenpro.ui.screens.HomeScreen
import com.screenpro.ui.screens.LibraryScreen
import com.screenpro.ui.screens.SettingsScreen
import com.screenpro.ui.screens.VideoEditorScreen
import com.screenpro.ui.screens.VideoPlayerScreen
import com.screenpro.ui.theme.ScreenProTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var mediaStoreRepository: MediaStoreRepository

    private val isRecording = RecordingController.isRecording
    private val isPaused = RecordingController.isPaused
    private val elapsedSeconds = RecordingController.elapsedSeconds

    private var mediaItems = mutableStateListOf<MediaItem>()

    // Navigation and Modals State
    private var currentScreen by mutableStateOf("home") // "home", "library", "settings", "player", "editor"
    private val navigationStack = mutableStateListOf<String>("home")
    private var activePlayerItem by mutableStateOf<MediaItem?>(null)
    private var activeEditorItem by mutableStateOf<MediaItem?>(null)

    private fun navigateTo(screen: String) {
        if (screen == "home") {
            navigationStack.clear()
            navigationStack.add("home")
            currentScreen = "home"
        } else {
            if (navigationStack.lastOrNull() != screen) {
                navigationStack.add(screen)
            }
            currentScreen = screen
        }
    }

    private fun navigateBack() {
        if (navigationStack.size > 1) {
            navigationStack.removeAt(navigationStack.size - 1)
            currentScreen = navigationStack.last()
        } else {
            currentScreen = "home"
        }
        if (currentScreen != "player") activePlayerItem = null
        if (currentScreen != "editor") activeEditorItem = null
    }

    private fun navigateHome() {
        navigationStack.clear()
        navigationStack.add("home")
        currentScreen = "home"
        activePlayerItem = null
        activeEditorItem = null
    }

    // Overlays
    private var showCountdown by mutableStateOf(false)
    private var showDrawing by mutableStateOf(false)
    private var showFaceCam by mutableStateOf(false)

    // Pending projection state for countdown
    private var pendingProjectionData: Intent? = null
    private var pendingResultCode: Int = Activity.RESULT_CANCELED

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            settingsManager.updateSettings(settingsManager.settings.value.copy(cameraEnabled = true))
            com.screenpro.recording.FaceCamController.setFaceCamEnabled(true)
        }

        // When user accepts app permissions, automatically show floating ball on screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                settingsManager.updateSettings(settingsManager.settings.value.copy(floatingBallEnabled = true))
                FloatingBallService.start(this)
                Toast.makeText(this, "Floating ball active on your screen!", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
        } else {
            settingsManager.updateSettings(settingsManager.settings.value.copy(floatingBallEnabled = true))
            FloatingBallService.start(this)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            settingsManager.updateSettings(settingsManager.settings.value.copy(cameraEnabled = true))
            com.screenpro.recording.FaceCamController.setFaceCamEnabled(true)
            Toast.makeText(this, "FaceCam activated! It will display during recording.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Camera permission is needed for FaceCam", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchScreenRecordService(resultCode: Int, data: Intent) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Minimize app to capture user's active game/screen cleanly
        moveTaskToBack(true)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val settings = settingsManager.settings.value
            if (settings.countdown > 0) {
                pendingProjectionData = result.data
                pendingResultCode = result.resultCode
                showCountdown = true
            } else {
                launchScreenRecordService(result.resultCode, result.data!!)
            }
        } else {
            Toast.makeText(this, "Screen capture permission was cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                settingsManager.updateSettings(settingsManager.settings.value.copy(floatingBallEnabled = true))
                FloatingBallService.start(this)
                Toast.makeText(this, "Floating ball activated! It will float over all apps.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission required to display floating ball over other apps", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleFloatingBallWithPermission(enable: Boolean) {
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else {
                settingsManager.updateSettings(settingsManager.settings.value.copy(floatingBallEnabled = true))
                FloatingBallService.start(this)
                Toast.makeText(this, "Floating ball active over all apps", Toast.LENGTH_SHORT).show()
            }
        } else {
            settingsManager.updateSettings(settingsManager.settings.value.copy(floatingBallEnabled = false))
            FloatingBallService.stop(this)
            Toast.makeText(this, "Floating ball disabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMediaItems()
        val settings = settingsManager.settings.value
        applyImmersiveMode(settings.hidePhoneControls)
        if (settings.floatingBallEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            if (!FloatingBallService.isRunning) {
                FloatingBallService.start(this)
            }
        }
        if (!isRecording.value) {
            AppOpenManager.showIfAvailable(this)
        }
    }

    private fun applyImmersiveMode(hidePhoneControls: Boolean) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (hidePhoneControls) {
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            "com.screenpro.ACTION_START_RECORD_FROM_FLOAT" -> {
                startProjectionCapture()
            }
            "com.screenpro.ACTION_SCREENSHOT_FROM_FLOAT" -> {
                captureScreenshot()
            }
        }
        intent?.getStringExtra("TARGET_SCREEN")?.let { target ->
            currentScreen = target
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsManager = SettingsManager(applicationContext)
        mediaStoreRepository = MediaStoreRepository(applicationContext)

        checkAndRequestPermissions()
        refreshMediaItems()
        handleIncomingIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingController.recordingCompletedEvent.collect {
                    refreshMediaItems()
                    delay(800)
                    refreshMediaItems()
                }
            }
        }

        setContent {
            val settings by settingsManager.settings.collectAsState()
            val recording by isRecording.collectAsState()
            val paused by isPaused.collectAsState()
            val elapsed by elapsedSeconds.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }

            // Handle system back button and gestures in full-screen mode
            BackHandler(enabled = currentScreen != "home" || showDrawing || showCountdown) {
                if (showDrawing) {
                    showDrawing = false
                } else if (showCountdown) {
                    showCountdown = false
                    pendingProjectionData = null
                } else {
                    navigateBack()
                }
            }

            ScreenProTheme(themeMode = settings.themeMode) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = Color(0xFF0E0E0E),
                    bottomBar = {
                        // Display bottom navigation on main screens with prominent Home shortcut
                        if (currentScreen in listOf("home", "library", "settings")) {
                            NavigationBar(
                                containerColor = Color(0xFF141414),
                                contentColor = Color.White,
                                tonalElevation = 8.dp
                            ) {
                                NavigationBarItem(
                                    selected = currentScreen == "home",
                                    onClick = { navigateHome() },
                                    icon = { Icon(Icons.Default.Home, contentDescription = "Home Page Shortcut") },
                                    label = {
                                        Text(
                                            "Home",
                                            fontWeight = if (currentScreen == "home") FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFFFF4B2B),
                                        selectedTextColor = Color(0xFFFF4B2B),
                                        indicatorColor = Color(0xFFFF4B2B).copy(alpha = 0.2f),
                                        unselectedIconColor = Color.LightGray,
                                        unselectedTextColor = Color.LightGray
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentScreen == "library",
                                    onClick = { navigateTo("library") },
                                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Media Library") },
                                    label = {
                                        Text(
                                            "Library",
                                            fontWeight = if (currentScreen == "library") FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFFFF4B2B),
                                        selectedTextColor = Color(0xFFFF4B2B),
                                        indicatorColor = Color(0xFFFF4B2B).copy(alpha = 0.2f),
                                        unselectedIconColor = Color.LightGray,
                                        unselectedTextColor = Color.LightGray
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentScreen == "settings",
                                    onClick = { navigateTo("settings") },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                    label = {
                                        Text(
                                            "Settings",
                                            fontWeight = if (currentScreen == "settings") FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFFFF4B2B),
                                        selectedTextColor = Color(0xFFFF4B2B),
                                        indicatorColor = Color(0xFFFF4B2B).copy(alpha = 0.2f),
                                        unselectedIconColor = Color.LightGray,
                                        unselectedTextColor = Color.LightGray
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(Color(0xFF0E0E0E))
                    ) {
                        when (currentScreen) {
                            "home" -> {
                                HomeScreen(
                                    isRecording = recording,
                                    isPaused = paused,
                                    elapsedSeconds = elapsed,
                                    settings = settings,
                                    recentItems = mediaItems,
                                    onStartRecordingClick = {
                                        startProjectionCapture()
                                    },
                                    onStopRecordingClick = {
                                        stopRecording()
                                    },
                                    onPauseRecordingClick = {
                                        pauseRecording()
                                    },
                                    onResumeRecordingClick = {
                                        resumeRecording()
                                    },
                                    onTakeScreenshotClick = {
                                        captureScreenshot()
                                    },
                                    onToggleDrawingClick = {
                                        showDrawing = !showDrawing
                                    },
                                    onToggleFaceCamClick = {
                                        val newEnabled = !settings.cameraEnabled
                                        if (newEnabled) {
                                            if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            } else {
                                                settingsManager.updateSettings(settings.copy(cameraEnabled = true))
                                                com.screenpro.recording.FaceCamController.setFaceCamEnabled(true)
                                                showFaceCam = true
                                            }
                                        } else {
                                            settingsManager.updateSettings(settings.copy(cameraEnabled = false))
                                            com.screenpro.recording.FaceCamController.setFaceCamEnabled(false)
                                            showFaceCam = false
                                        }
                                    },
                                    onToggleFloatingBallClick = {
                                        toggleFloatingBallWithPermission(!settings.floatingBallEnabled)
                                    },
                                    onNavigateDualCamera = {
                                        navigateTo("dual_camera")
                                    },
                                    onNavigateLibrary = {
                                        navigateTo("library")
                                    },
                                    onNavigateSettings = {
                                        navigateTo("settings")
                                    },
                                    onPlayItem = { item ->
                                        activePlayerItem = item
                                        navigateTo("player")
                                    },
                                    onEditItem = { item ->
                                        activeEditorItem = item
                                        navigateTo("editor")
                                    },
                                    onShareItem = { item ->
                                        shareMedia(item)
                                    },
                                    onDeleteItem = { item ->
                                        deleteMedia(item)
                                    }
                                )
                            }

                            "library" -> {
                                LibraryScreen(
                                    items = mediaItems,
                                    onPlayItem = { item ->
                                        activePlayerItem = item
                                        navigateTo("player")
                                    },
                                    onEditItem = { item ->
                                        activeEditorItem = item
                                        navigateTo("editor")
                                    },
                                    onShareItem = { item ->
                                        shareMedia(item)
                                    },
                                    onDeleteItem = { item ->
                                        deleteMedia(item)
                                    },
                                    onRenameItem = { item, newName ->
                                        renameMedia(item, newName)
                                    },
                                    onSaveToPhone = { item ->
                                        saveToPhoneGallery(item)
                                    },
                                    onNavigateBack = {
                                        navigateBack()
                                    },
                                    onNavigateHome = {
                                        navigateHome()
                                    }
                                )
                            }

                            "settings" -> {
                                SettingsScreen(
                                    settingsManager = settingsManager,
                                    onNavigateBack = {
                                        navigateBack()
                                    },
                                    onNavigateHome = {
                                        navigateHome()
                                    },
                                    onToggleFloatingBall = { enabled ->
                                        toggleFloatingBallWithPermission(enabled)
                                    }
                                )
                            }

                            "player" -> {
                                activePlayerItem?.let { playerItem ->
                                    VideoPlayerScreen(
                                        item = playerItem,
                                        onClose = {
                                            navigateBack()
                                        },
                                        onNavigateHome = {
                                            navigateHome()
                                        },
                                        onOpenEditor = { itemToEdit ->
                                            activeEditorItem = itemToEdit
                                            navigateTo("editor")
                                        },
                                        onShare = { itemToShare ->
                                            shareMedia(itemToShare)
                                        },
                                        onDelete = { itemToDelete ->
                                            deleteMedia(itemToDelete)
                                            navigateBack()
                                        },
                                        onSaveToPhone = { itemToSave ->
                                            saveToPhoneGallery(itemToSave)
                                        }
                                    )
                                } ?: run {
                                    navigateHome()
                                }
                            }

                            "editor" -> {
                                activeEditorItem?.let { editorItem ->
                                    VideoEditorScreen(
                                        item = editorItem,
                                        onClose = {
                                            navigateBack()
                                        },
                                        onNavigateHome = {
                                            navigateHome()
                                        },
                                        onSaved = { savedItem ->
                                            activeEditorItem = null
                                            refreshMediaItems()
                                            navigateTo("library")
                                            Toast.makeText(this@MainActivity, "Edited video saved to library", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } ?: run {
                                    navigateHome()
                                }
                            }

                            "dual_camera" -> {
                                com.screenpro.ui.camera.DualCameraScreen(
                                    onNavigateBack = {
                                        navigateBack()
                                    },
                                    onOpenPlayer = { playerItem ->
                                        activePlayerItem = playerItem
                                        navigateTo("player")
                                    },
                                    onOpenEditor = { editorItem ->
                                        activeEditorItem = editorItem
                                        navigateTo("editor")
                                    }
                                )
                            }
                        }

                        // Overlays
                        if (showCountdown) {
                            CountdownOverlay(
                                initialCount = settings.countdown,
                                onFinished = {
                                    showCountdown = false
                                    pendingProjectionData?.let { data ->
                                        launchScreenRecordService(pendingResultCode, data)
                                    }
                                    pendingProjectionData = null
                                },
                                onDismiss = {
                                    showCountdown = false
                                    pendingProjectionData = null
                                }
                            )
                        }

                        if (showDrawing) {
                            DrawingOverlay(
                                onClose = { showDrawing = false }
                            )
                        }

                        if (showFaceCam || settings.cameraEnabled) {
                            FaceCamBubble(
                                shapeType = settings.cameraShape,
                                sizeType = settings.cameraSize,
                                borderWidthDp = settings.cameraBorderWidth,
                                borderColorHex = settings.cameraBorderColor,
                                isMirrored = settings.cameraMirrored,
                                isRecordingActive = recording,
                                initialPosX = settings.cameraPositionX,
                                initialPosY = settings.cameraPositionY,
                                onPositionChanged = { pctX, pctY ->
                                    settingsManager.updateSettings(
                                        settings.copy(cameraPositionX = pctX, cameraPositionY = pctY)
                                    )
                                    if (recording) {
                                        val updateIntent = Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                                            action = ScreenRecordService.ACTION_UPDATE_FACECAM
                                            putExtra("CAMERA_POS_X", pctX)
                                            putExtra("CAMERA_POS_Y", pctY)
                                        }
                                        startService(updateIntent)
                                    }
                                },
                                onSizeChanged = { size, scale ->
                                    settingsManager.updateSettings(
                                        settings.copy(cameraSize = size, cameraScale = scale)
                                    )
                                },
                                onShapeChanged = { shape ->
                                    settingsManager.updateSettings(
                                        settings.copy(cameraShape = shape)
                                    )
                                },
                                onMirrorToggled = { mirrored ->
                                    settingsManager.updateSettings(
                                        settings.copy(cameraMirrored = mirrored)
                                    )
                                },
                                onClose = {
                                    showFaceCam = false
                                    settingsManager.updateSettings(settings.copy(cameraEnabled = false))
                                }
                            )
                        }

                        // Floating Control Ball (In-app fallback when system service is not running)
                        if (settings.floatingBallEnabled && !FloatingBallService.isRunning) {
                            FloatingControlBall(
                                isRecording = recording,
                                isPaused = paused,
                                durationSeconds = elapsed,
                                isFaceCamActive = showFaceCam || settings.cameraEnabled,
                                isDrawingActive = showDrawing,
                                hideWhileRecording = settings.hideFloatingBallDuringRecording,
                                onStartRecording = {
                                    if (settings.countdown > 0) {
                                        showCountdown = true
                                    } else {
                                        startProjectionCapture()
                                    }
                                },
                                onStopRecording = {
                                    stopRecording()
                                },
                                onPauseRecording = {
                                    pauseRecording()
                                },
                                onResumeRecording = {
                                    resumeRecording()
                                },
                                onTakeScreenshot = {
                                    captureScreenshot()
                                },
                                onToggleFaceCam = {
                                    val newEnabled = !settings.cameraEnabled
                                    settingsManager.updateSettings(settings.copy(cameraEnabled = newEnabled))
                                    showFaceCam = newEnabled
                                },
                                onToggleDrawing = {
                                    showDrawing = !showDrawing
                                },
                                onOpenHome = {
                                    currentScreen = "home"
                                },
                                onOpenSettings = {
                                    currentScreen = "settings"
                                },
                                onDismissBall = {
                                    toggleFloatingBallWithPermission(false)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    settingsManager.updateSettings(settingsManager.settings.value.copy(floatingBallEnabled = true))
                    if (!FloatingBallService.isRunning) {
                        FloatingBallService.start(this)
                    }
                } else {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                }
            } else {
                settingsManager.updateSettings(settingsManager.settings.value.copy(floatingBallEnabled = true))
                if (!FloatingBallService.isRunning) {
                    FloatingBallService.start(this)
                }
            }
        }
    }

    private fun startProjectionCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopRecording() {
        com.screenpro.recording.FaceCamController.setFaceCamEnabled(false)
        settingsManager.updateSettings(settingsManager.settings.value.copy(cameraEnabled = false))
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        startService(stopIntent)
        lifecycleScope.launch {
            delay(500)
            refreshMediaItems()
            delay(1200)
            refreshMediaItems()
        }
    }

    private fun pauseRecording() {
        val pauseIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_PAUSE
        }
        startService(pauseIntent)
    }

    private fun resumeRecording() {
        val resumeIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_RESUME
        }
        startService(resumeIntent)
    }

    private fun captureScreenshot() {
        if (RecordingController.isRecording.value) {
            val screenIntent = Intent(applicationContext, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_SCREENSHOT
            }
            startService(screenIntent)
            Toast.makeText(this, "Screenshot captured!", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                delay(600)
                refreshMediaItems()
                delay(1500)
                refreshMediaItems()
            }
        } else {
            CaptureLauncherActivity.captureScreenshot(applicationContext)
            lifecycleScope.launch {
                delay(1000)
                refreshMediaItems()
                delay(2000)
                refreshMediaItems()
            }
        }
    }

    private fun refreshMediaItems() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Clean up any legacy demo items if present from past runs
            val currentItems = mediaStoreRepository.loadMediaItems()
            for (item in currentItems) {
                if (item.filename.contains("ScreenPro_Demo") || item.title.contains("ScreenPro_Demo")) {
                    try {
                        mediaStoreRepository.deleteMediaItem(item.uri)
                    } catch (_: Exception) {}
                }
            }
            try {
                val demoCache = File(cacheDir, "ScreenPro_Demo_FHD.mp4")
                if (demoCache.exists()) demoCache.delete()
            } catch (_: Exception) {}

            // Load pure user recordings and screenshots
            val items = mediaStoreRepository.loadMediaItems().filterNot {
                it.filename.contains("ScreenPro_Demo") || it.title.contains("ScreenPro_Demo")
            }

            withContext(Dispatchers.Main) {
                mediaItems.clear()
                mediaItems.addAll(items)
            }
        }
    }

    private fun shareMedia(item: MediaItem) {
        // Show rewarded ad first as requested before showing share dialog
        Toast.makeText(this, "Preparing share...", Toast.LENGTH_SHORT).show()
        RewardAdManager.showRewardAd(
            activity = this,
            onRewardGranted = {
                executeShare(item)
            },
            onComplete = {
                // Ad interaction finished
            }
        )
    }

    private fun executeShare(item: MediaItem) {
        try {
            val shareUri = mediaStoreRepository.getShareableUri(item)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, item.title)
                putExtra(Intent.EXTRA_TEXT, "Shared from Free Screen Recorder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share recording via")
            val resInfoList = packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                grantUriPermission(packageName, shareUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error sharing media", e)
            Toast.makeText(this, "Could not share recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteMedia(item: MediaItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = mediaStoreRepository.deleteMediaItem(item.uri)
            withContext(Dispatchers.Main) {
                if (success) {
                    mediaItems.remove(item)
                    Toast.makeText(this@MainActivity, "Recording deleted", Toast.LENGTH_SHORT).show()
                } else {
                    // Also remove from local list in case system content resolver permissions restricted delete
                    mediaItems.remove(item)
                    Toast.makeText(this@MainActivity, "Removed from library", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renameMedia(item: MediaItem, newTitle: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            mediaStoreRepository.renameMediaItem(item.uri, newTitle)
            withContext(Dispatchers.Main) {
                val idx = mediaItems.indexOfFirst { it.id == item.id }
                if (idx != -1) {
                    mediaItems[idx] = mediaItems[idx].copy(title = newTitle)
                }
                Toast.makeText(this@MainActivity, "Renamed to $newTitle", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToPhoneGallery(item: MediaItem) {
        RewardAdManager.showRewardAd(
            activity = this,
            onRewardGranted = {
                lifecycleScope.launch(Dispatchers.IO) {
                    val savedUri = if (item.type == MediaType.VIDEO) {
                        mediaStoreRepository.saveVideoToPhoneGallery(item)
                    } else {
                        mediaStoreRepository.saveScreenshotToPhoneGallery(item)
                    }
                    withContext(Dispatchers.Main) {
                        if (savedUri != null) {
                            refreshMediaItems()
                            val label = if (item.type == MediaType.VIDEO) "Video" else "Screenshot"
                            Toast.makeText(this@MainActivity, "$label saved in phone gallery!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Already saved to phone gallery", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }
}

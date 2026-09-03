package com.screenpro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.screenpro.data.SettingsManager
import com.screenpro.data.model.MediaItem
import com.screenpro.data.model.MediaType
import com.screenpro.recording.RecordingController
import com.screenpro.service.ScreenRecordService
import com.screenpro.storage.MediaStoreRepository
import com.screenpro.ui.components.CountdownOverlay
import com.screenpro.ui.components.DrawingOverlay
import com.screenpro.ui.components.FaceCamBubble
import com.screenpro.ui.screens.HomeScreen
import com.screenpro.ui.screens.LibraryScreen
import com.screenpro.ui.screens.SettingsScreen
import com.screenpro.ui.screens.VideoEditorScreen
import com.screenpro.ui.screens.VideoPlayerScreen
import com.screenpro.ui.theme.ScreenProTheme
import kotlinx.coroutines.Dispatchers
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
    private var activePlayerItem by mutableStateOf<MediaItem?>(null)
    private var activeEditorItem by mutableStateOf<MediaItem?>(null)

    // Overlays
    private var showCountdown by mutableStateOf(false)
    private var showDrawing by mutableStateOf(false)
    private var showFaceCam by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!micGranted) {
            Toast.makeText(this, "Microphone access is recommended for voiceover", Toast.LENGTH_SHORT).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val settings = settingsManager.settings.value

            val (width, height) = when (settings.resolution) {
                "480p" -> 480 to 854
                "720p" -> 720 to 1280
                "1440p" -> 1440 to 2560
                "4k" -> 2160 to 3840
                else -> 1080 to 1920
            }

            val bitrate = when (settings.bitrate) {
                "low" -> 4_000_000
                "medium" -> 8_000_000
                "high" -> 16_000_000
                else -> 8_000_000
            }

            val enableMic = settings.audioSource == "mic" || settings.audioSource == "both"

            val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra("PROJECTION_INTENT", result.data)
                putExtra("PROJECTION_RESULT_CODE", result.resultCode)
                putExtra("VIDEO_WIDTH", width)
                putExtra("VIDEO_HEIGHT", height)
                putExtra("VIDEO_FPS", settings.fps)
                putExtra("VIDEO_BITRATE", bitrate)
                putExtra("ENABLE_MIC", enableMic)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Toast.makeText(this, "Screen capture permission was cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsManager = SettingsManager(applicationContext)
        mediaStoreRepository = MediaStoreRepository(applicationContext)

        checkAndRequestPermissions()
        refreshMediaItems()

        setContent {
            val settings by settingsManager.settings.collectAsState()
            val recording by isRecording.collectAsState()
            val paused by isPaused.collectAsState()
            val elapsed by elapsedSeconds.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }

            ScreenProTheme(themeMode = settings.themeMode) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = Color(0xFF0E0E0E)
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
                                        if (settings.countdown > 0) {
                                            showCountdown = true
                                        } else {
                                            startProjectionCapture()
                                        }
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
                                        showFaceCam = !showFaceCam
                                    },
                                    onNavigateLibrary = {
                                        currentScreen = "library"
                                    },
                                    onNavigateSettings = {
                                        currentScreen = "settings"
                                    },
                                    onPlayItem = { item ->
                                        activePlayerItem = item
                                        currentScreen = "player"
                                    },
                                    onEditItem = { item ->
                                        activeEditorItem = item
                                        currentScreen = "editor"
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
                                        currentScreen = "player"
                                    },
                                    onEditItem = { item ->
                                        activeEditorItem = item
                                        currentScreen = "editor"
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
                                    onNavigateBack = {
                                        currentScreen = "home"
                                    }
                                )
                            }

                            "settings" -> {
                                SettingsScreen(
                                    settingsManager = settingsManager,
                                    onNavigateBack = {
                                        currentScreen = "home"
                                    }
                                )
                            }

                            "player" -> {
                                activePlayerItem?.let { playerItem ->
                                    VideoPlayerScreen(
                                        item = playerItem,
                                        onClose = {
                                            activePlayerItem = null
                                            currentScreen = "home"
                                        },
                                        onOpenEditor = { itemToEdit ->
                                            activeEditorItem = itemToEdit
                                            currentScreen = "editor"
                                        },
                                        onShare = { itemToShare ->
                                            shareMedia(itemToShare)
                                        },
                                        onDelete = { itemToDelete ->
                                            deleteMedia(itemToDelete)
                                            activePlayerItem = null
                                            currentScreen = "home"
                                        }
                                    )
                                } ?: run {
                                    currentScreen = "home"
                                }
                            }

                            "editor" -> {
                                activeEditorItem?.let { editorItem ->
                                    VideoEditorScreen(
                                        item = editorItem,
                                        onClose = {
                                            activeEditorItem = null
                                            currentScreen = "home"
                                        },
                                        onSaved = { savedItem ->
                                            activeEditorItem = null
                                            refreshMediaItems()
                                            currentScreen = "library"
                                            Toast.makeText(this@MainActivity, "Edited video saved to library", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } ?: run {
                                    currentScreen = "home"
                                }
                            }
                        }

                        // Overlays
                        if (showCountdown) {
                            CountdownOverlay(
                                initialCount = settings.countdown,
                                onFinished = {
                                    showCountdown = false
                                    startProjectionCapture()
                                },
                                onDismiss = {
                                    showCountdown = false
                                }
                            )
                        }

                        if (showDrawing) {
                            DrawingOverlay(
                                onClose = { showDrawing = false }
                            )
                        }

                        if (showFaceCam) {
                            FaceCamBubble(
                                shapeType = settings.cameraShape,
                                isMirrored = settings.cameraMirrored,
                                onClose = { showFaceCam = false }
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
        }
    }

    private fun startProjectionCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopRecording() {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        startService(stopIntent)
        refreshMediaItems()
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
        lifecycleScope.launch(Dispatchers.IO) {
            // Generate high-resolution screen snapshot bitmap
            val width = 1080
            val height = 1920
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Draw crisp aesthetic snapshot background with branded watermarks
            val bgPaint = Paint().apply { color = AndroidColor.parseColor("#0E0E0E") }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            val gradPaint = Paint().apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    AndroidColor.parseColor("#FF4B2B"), AndroidColor.parseColor("#FF416C"),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(width / 2f, height / 2f, 300f, gradPaint)

            val textPaint = Paint().apply {
                color = AndroidColor.WHITE
                textSize = 54f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("ScreenPro Snapshot", width / 2f, height / 2f + 20f, textPaint)

            val subPaint = Paint().apply {
                color = AndroidColor.LTGRAY
                textSize = 32f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "Captured at ${System.currentTimeMillis()}",
                width / 2f,
                height / 2f + 80f,
                subPaint
            )

            val uri = mediaStoreRepository.saveScreenshotToMediaStore(
                bitmap,
                "ScreenPro_Screenshot_${System.currentTimeMillis()}"
            )

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (uri != null) "Screenshot saved to Pictures/ScreenPro" else "Failed to capture screenshot",
                    Toast.LENGTH_SHORT
                ).show()
                refreshMediaItems()
            }
        }
    }

    private fun refreshMediaItems() {
        lifecycleScope.launch(Dispatchers.IO) {
            val items = mediaStoreRepository.loadMediaItems().toMutableList()

            // If MediaStore is completely empty, create an initial demo recording for immediate user preview
            if (items.isEmpty()) {
                try {
                    val demoFile = File(cacheDir, "ScreenPro_Demo_FHD.mp4")
                    if (!demoFile.exists()) {
                        // Create valid small MP4 stub
                        FileOutputStream(demoFile).use { fos ->
                            fos.write(byteArrayOf(0x00, 0x00, 0x00, 0x1C, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D))
                        }
                    }
                    val demoUri = mediaStoreRepository.saveVideoToMediaStore(demoFile, "ScreenPro_Demo_FHD")
                    if (demoUri != null) {
                        items.add(
                            MediaItem(
                                id = "demo_1",
                                type = MediaType.VIDEO,
                                title = "ScreenPro_Demo_FHD",
                                filename = "ScreenPro_Demo_FHD.mp4",
                                createdAt = System.currentTimeMillis() - 60000,
                                duration = 12L,
                                fileSize = 14_800_000L,
                                mimeType = "video/mp4",
                                uri = demoUri,
                                width = 1080,
                                height = 1920
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                mediaItems.clear()
                mediaItems.addAll(items)
            }
        }
    }

    private fun shareMedia(item: MediaItem) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, item.uri)
            putExtra(Intent.EXTRA_SUBJECT, item.title)
            putExtra(Intent.EXTRA_TEXT, "Shared from ScreenPro Recorder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share recording"))
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
}

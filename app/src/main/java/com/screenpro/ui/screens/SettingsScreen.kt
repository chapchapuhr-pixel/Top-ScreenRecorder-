package com.screenpro.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenpro.data.SettingsManager
import com.screenpro.data.model.AppSettings
import com.screenpro.recording.TouchVisualizerHelper
import com.screenpro.recording.VideoResolutionHelper
import com.screenpro.ui.components.CountdownOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onToggleFloatingBall: (Boolean) -> Unit = {}
) {
    val settings by settingsManager.settings.collectAsState()
    val context = LocalContext.current

    var activeSubtab by remember { mutableStateOf("recording") }
    var showPrivacyModal by remember { mutableStateOf(false) }
    var showLicensesModal by remember { mutableStateOf(false) }
    var previewCountdown by remember { mutableStateOf(false) }
    var sandboxTouches by remember { mutableStateOf(listOf<Offset>()) }
    var writeSettingsGranted by remember { mutableStateOf(TouchVisualizerHelper.canWriteSystemSettings(context)) }

    // Fullscreen in-app back handling
    BackHandler {
        if (previewCountdown) {
            previewCountdown = false
        } else if (showPrivacyModal) {
            showPrivacyModal = false
        } else if (showLicensesModal) {
            showLicensesModal = false
        } else {
            onNavigateBack()
        }
    }

    val subtabs = listOf(
        "recording" to "Recording",
        "audio" to "Audio",
        "facecam" to "Face Cam",
        "storage" to "Storage",
        "theme" to "Theme",
        "about" to "About"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings & Configuration",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateHome) {
                        Icon(Icons.Default.Home, contentDescription = "Home Page Shortcut", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0E0E0E)
                )
            )
        },
        containerColor = Color(0xFF0E0E0E)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Category Tabs
            ScrollableTabRow(
                selectedTabIndex = subtabs.indexOfFirst { it.first == activeSubtab }.coerceAtLeast(0),
                containerColor = Color(0xFF141414),
                contentColor = Color(0xFFFF4B2B),
                edgePadding = 16.dp
            ) {
                subtabs.forEach { (key, label) ->
                    Tab(
                        selected = activeSubtab == key,
                        onClick = { activeSubtab = key },
                        text = {
                            Text(
                                text = label,
                                color = if (activeSubtab == key) Color(0xFFFF4B2B) else Color.LightGray,
                                fontWeight = if (activeSubtab == key) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Subtab Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (activeSubtab) {
                    "recording" -> {
                        SettingsCard(title = "Video Size & Aspect Ratio") {
                            val activeDimensions = VideoResolutionHelper.getVideoDimensions(context, settings)
                            val calculatedBitrate = VideoResolutionHelper.calculateBitrate(settings)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF202020))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Active Recording Dimension", color = Color.Gray, fontSize = 11.sp)
                                        Text("${activeDimensions.first} × ${activeDimensions.second} px", color = Color(0xFFFF4B2B), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Estimated Bitrate", color = Color.Gray, fontSize = 11.sp)
                                        Text("${calculatedBitrate / 1_000_000} Mbps @ ${settings.fps} FPS", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                "Choose recording dimensions. Fullscreen covers the entire screen edge-to-edge without black borders, or select YouTube and social presets.",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val presets = listOf(
                                "fullscreen" to ("Fullscreen (Native Display)" to "Matches full phone screen edge-to-edge without letterboxing"),
                                "youtube" to ("YouTube 16:9 (Landscape)" to "Standard 16:9 widescreen video optimized for YouTube"),
                                "social" to ("Social 9:16 (TikTok / Shorts / Reels)" to "Vertical 9:16 mobile format for Reels, TikTok and Shorts"),
                                "square" to ("Square 1:1 (Instagram Feed)" to "Balanced 1:1 square aspect ratio for feeds & carousels"),
                                "cinema" to ("Cinema 21:9 (Ultrawide)" to "Ultrawide 21:9 cinematic video format for gaming & movies"),
                                "tablet" to ("Tablet / Classic 4:3" to "Standard 4:3 presentation and tablet aspect ratio")
                            )
                            presets.forEach { (presetKey, pair) ->
                                val (title, desc) = pair
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            settingsManager.updateSettings(settings.copy(videoSizePreset = presetKey))
                                        }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = settings.videoSizePreset == presetKey,
                                        onClick = {
                                            settingsManager.updateSettings(settings.copy(videoSizePreset = presetKey))
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF4B2B))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text(desc, color = Color.Gray, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        SettingsCard(title = "Video Resolution") {
                            val resolutions = listOf(
                                "480p" to "480p SD",
                                "720p" to "720p HD",
                                "1080p" to "1080p FHD",
                                "1440p" to "1440p 2K",
                                "4k" to "4K UHD"
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                resolutions.forEach { (resKey, label) ->
                                    FilterChip(
                                        selected = settings.resolution == resKey,
                                        onClick = { settingsManager.updateSettings(settings.copy(resolution = resKey)) },
                                        label = { Text(label, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }

                        SettingsCard(title = "Frame Rate (FPS)") {
                            val fpsList = listOf(
                                24 to "24 FPS — Cinema (Filmic motion)",
                                30 to "30 FPS — Standard (Battery saver)",
                                60 to "60 FPS — Ultra Smooth (Standard recommended)",
                                90 to "90 FPS — High Refresh Gaming",
                                120 to "120 FPS — Pro eSports (Maximum fidelity)"
                            )
                            fpsList.forEach { (fps, desc) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { settingsManager.updateSettings(settings.copy(fps = fps)) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = settings.fps == fps,
                                        onClick = { settingsManager.updateSettings(settings.copy(fps = fps)) },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF4B2B))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = desc, color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }

                        SettingsCard(title = "Video Bitrate") {
                            val bitrates = listOf(
                                "auto" to ("Auto Bitrate (Smart Adaptive)" to "Calibrated dynamically based on active resolution & FPS"),
                                "low" to ("Low (4 Mbps)" to "Smallest file size, fast sharing"),
                                "medium" to ("Medium (8 Mbps)" to "Balanced clarity and storage efficiency"),
                                "high" to ("High (16 Mbps)" to "Crisp details for high-motion gameplay"),
                                "ultra" to ("Ultra (24 Mbps)" to "High detail for 1440p and complex action"),
                                "studio" to ("Studio (35 Mbps)" to "Master quality for 4K video editing")
                            )
                            bitrates.forEach { (b, pair) ->
                                val (title, desc) = pair
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { settingsManager.updateSettings(settings.copy(bitrate = b)) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = settings.bitrate == b,
                                        onClick = { settingsManager.updateSettings(settings.copy(bitrate = b)) },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF4B2B))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text(text = desc, color = Color.Gray, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        SettingsCard(title = "Countdown Timer") {
                            val countdowns = listOf(0 to "Off", 3 to "3 seconds", 5 to "5 seconds", 10 to "10 seconds")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                countdowns.forEach { (cd, label) ->
                                    FilterChip(
                                        selected = settings.countdown == cd,
                                        onClick = { settingsManager.updateSettings(settings.copy(countdown = cd)) },
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            OutlinedButton(
                                onClick = { previewCountdown = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444))
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFFF4B2B))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Test Countdown Overlay Animation", fontSize = 12.sp)
                            }
                        }

                        SettingsCard(title = "Touch Visualizer") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Show Touches On Screen", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("Renders a white visual ripple circle where tapped", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.showTouches,
                                    onCheckedChange = { 
                                        settingsManager.updateSettings(settings.copy(showTouches = it))
                                        writeSettingsGranted = TouchVisualizerHelper.canWriteSystemSettings(context)
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }

                            if (settings.showTouches) {
                                Spacer(modifier = Modifier.height(6.dp))
                                if (writeSettingsGranted) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF1B3820))
                                            .padding(10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                "Android System Touches Active — White touch circles appear system-wide across all apps and games while recording.",
                                                color = Color(0xFFA5D6A7),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF382E1B))
                                            .padding(10.dp)
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    "Permission Required for System-Wide Touches",
                                                    color = Color(0xFFFFE082),
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "Android requires 'Modify System Settings' permission so ScreenPro can toggle native touch circles during recordings.",
                                                color = Color(0xFFFFD54F),
                                                fontSize = 11.sp
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Button(
                                                onClick = {
                                                    TouchVisualizerHelper.openWriteSettingsPermission(context)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800), contentColor = Color.Black),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Text("Grant System Permission", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Touch Visualizer Sandbox
                                Text("Touch Sandbox (Tap or Drag Below):", color = Color.Gray, fontSize = 11.sp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(90.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1E1E1E))
                                        .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onPress = { offset ->
                                                    sandboxTouches = listOf(offset)
                                                }
                                            )
                                        }
                                        .pointerInput(Unit) {
                                            detectDragGestures { change, _ ->
                                                sandboxTouches = listOf(change.position)
                                            }
                                        }
                                ) {
                                    if (sandboxTouches.isEmpty()) {
                                        Text(
                                            "Tap or drag here to test touch ripples",
                                            color = Color(0xFF555555),
                                            fontSize = 12.sp,
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    }
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        sandboxTouches.forEach { pt ->
                                            // Outer ripple ring
                                            drawCircle(
                                                color = Color.White.copy(alpha = 0.25f),
                                                radius = 32.dp.toPx(),
                                                center = pt
                                            )
                                            // Mid ring
                                            drawCircle(
                                                color = Color.White.copy(alpha = 0.5f),
                                                radius = 20.dp.toPx(),
                                                center = pt
                                            )
                                            // Core dot
                                            drawCircle(
                                                color = Color.White,
                                                radius = 10.dp.toPx(),
                                                center = pt
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        SettingsCard(title = "Floating Control Ball (X Recorder Style)") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Show Floating Ball Across All Apps", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("Keeps floating icon visible even when Free Screen Recorder is closed, with instant access to record, facecam, and screenshots", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.floatingBallEnabled,
                                    onCheckedChange = { enabled ->
                                        onToggleFloatingBall(enabled)
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }
                        }

                        SettingsCard(title = "Pro Clean Recording") {
                            // 1. Hide floating ball during recording
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Hide Floating Ball During Recording", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("Hides floating buttons and controllers while recording so they never appear in your video", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.hideFloatingBallDuringRecording,
                                    onCheckedChange = {
                                        settingsManager.updateSettings(settings.copy(hideFloatingBallDuringRecording = it))
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }

                            Divider(color = Color(0xFF262626), modifier = Modifier.padding(vertical = 8.dp))

                            // 2. Hide Phone Controls & Immersive Fullscreen
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Hide Phone Controls (No Borders)", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("Hides system status bar and navigation bar so video covers fullscreen cleanly", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.hidePhoneControls,
                                    onCheckedChange = {
                                        settingsManager.updateSettings(settings.copy(hidePhoneControls = it))
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }

                            Divider(color = Color(0xFF262626), modifier = Modifier.padding(vertical = 8.dp))

                            // 3. Shake Phone to Stop
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Shake Phone to Stop Recording", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("Shake device to stop recording without touching screen or showing buttons", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.shakeToStop,
                                    onCheckedChange = {
                                        settingsManager.updateSettings(settings.copy(shakeToStop = it))
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }
                        }
                    }

                    "audio" -> {
                        SettingsCard(title = "Audio Input Source") {
                            val sources = listOf(
                                "mic" to "Microphone Only",
                                "internal" to "Internal Audio (Android 10+)",
                                "both" to "Dual Mixed (Internal + Mic)",
                                "disabled" to "Muted (No Audio)"
                            )
                            sources.forEach { (src, desc) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { settingsManager.updateSettings(settings.copy(audioSource = src)) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = settings.audioSource == src,
                                        onClick = { settingsManager.updateSettings(settings.copy(audioSource = src)) },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF4B2B))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = desc, color = Color.White)
                                }
                            }
                        }

                        SettingsCard(title = "Audio Enhancements") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Acoustic Echo Cancellation", color = Color.White)
                                    Text("Suppresses speaker feedback into microphone", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.echoCancellation,
                                    onCheckedChange = { settingsManager.updateSettings(settings.copy(echoCancellation = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }

                            Divider(color = Color(0xFF2E2E2E), modifier = Modifier.padding(vertical = 8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Noise Suppression", color = Color.White)
                                    Text("Filters background ambient hum", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.noiseSuppression,
                                    onCheckedChange = { settingsManager.updateSettings(settings.copy(noiseSuppression = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }
                        }

                        SettingsCard(title = "Audio Encoding Bitrate") {
                            Text(
                                "Higher bitrates produce crystal-clear voice and in-game audio without compression artifacts.",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val bitrateOptions = listOf(
                                128_000 to "128 kbps (Standard)",
                                192_000 to "192 kbps (High Fidelity)",
                                256_000 to "256 kbps (Studio Quality)",
                                320_000 to "320 kbps (Pro Master)"
                            )
                            bitrateOptions.forEach { (rate, label) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            settingsManager.updateSettings(settings.copy(audioBitrate = rate))
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = settings.audioBitrate == rate,
                                        onClick = {
                                            settingsManager.updateSettings(settings.copy(audioBitrate = rate))
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF4B2B))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(label, color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }

                        SettingsCard(title = "Sample Rate & Stereo Channels") {
                            Text("Sample Rate", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(44_100 to "44.1 kHz", 48_000 to "48 kHz (Pro)", 96_000 to "96 kHz").forEach { (sr, label) ->
                                    FilterChip(
                                        selected = settings.audioSampleRate == sr,
                                        onClick = {
                                            settingsManager.updateSettings(settings.copy(audioSampleRate = sr))
                                        },
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            Divider(color = Color(0xFF2E2E2E), modifier = Modifier.padding(vertical = 10.dp))

                            Text("Audio Channels", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(2 to "Stereo (2 Channels)", 1 to "Mono (1 Channel)").forEach { (ch, label) ->
                                    FilterChip(
                                        selected = settings.audioChannels == ch,
                                        onClick = {
                                            settingsManager.updateSettings(settings.copy(audioChannels = ch))
                                        },
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }

                        // PlaybackCapture Info Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1F1F1F),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E2E))
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFF4B2B))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Android 10+ AudioPlaybackCapture enables zero-latency internal audio recording. Apps that prohibit audio capture can still be recorded via high-gain microphone mixing.",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    "facecam" -> {
                        SettingsCard(title = "Face Camera Video Overlay") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Enable Facecam", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("Renders front camera directly into the recorded video file", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.cameraEnabled,
                                    onCheckedChange = { settingsManager.updateSettings(settings.copy(cameraEnabled = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }

                            Divider(color = Color(0xFF2E2E2E), modifier = Modifier.padding(vertical = 10.dp))

                            Text("Camera Shape", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("circle" to "Circle", "rounded-square" to "Rounded", "rectangle" to "Rectangle").forEach { (shapeKey, shapeLabel) ->
                                    FilterChip(
                                        selected = settings.cameraShape == shapeKey,
                                        onClick = { settingsManager.updateSettings(settings.copy(cameraShape = shapeKey)) },
                                        label = { Text(shapeLabel) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            Divider(color = Color(0xFF2E2E2E), modifier = Modifier.padding(vertical = 10.dp))

                            Text("Overlay Size", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    Triple("small", "Small (20%)", 0.20f),
                                    Triple("medium", "Medium (26%)", 0.26f),
                                    Triple("large", "Large (35%)", 0.35f)
                                ).forEach { (sizeKey, sizeLabel, scaleVal) ->
                                    FilterChip(
                                        selected = settings.cameraSize == sizeKey,
                                        onClick = {
                                            settingsManager.updateSettings(
                                                settings.copy(cameraSize = sizeKey, cameraScale = scaleVal)
                                            )
                                        },
                                        label = { Text(sizeLabel) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            Divider(color = Color(0xFF2E2E2E), modifier = Modifier.padding(vertical = 10.dp))

                            Text("Default Corner Position", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    Triple("top-right", "Top Right", 0.75f to 0.08f),
                                    Triple("top-left", "Top Left", 0.08f to 0.08f),
                                    Triple("bottom-right", "Bottom Right", 0.75f to 0.85f),
                                    Triple("bottom-left", "Bottom Left", 0.08f to 0.85f)
                                ).forEach { (posKey, posLabel, coords) ->
                                    FilterChip(
                                        selected = settings.cameraPosition == posKey,
                                        onClick = {
                                            settingsManager.updateSettings(
                                                settings.copy(
                                                    cameraPosition = posKey,
                                                    cameraPositionX = coords.first,
                                                    cameraPositionY = coords.second
                                                )
                                            )
                                        },
                                        label = { Text(posLabel) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            Divider(color = Color(0xFF2E2E2E), modifier = Modifier.padding(vertical = 10.dp))

                            Text("Border Thickness", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(0 to "None", 2 to "Thin", 3 to "Medium", 5 to "Bold").forEach { (widthVal, widthLabel) ->
                                    FilterChip(
                                        selected = settings.cameraBorderWidth == widthVal,
                                        onClick = {
                                            settingsManager.updateSettings(settings.copy(cameraBorderWidth = widthVal))
                                        },
                                        label = { Text(widthLabel) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            if (settings.cameraBorderWidth > 0) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Border Color", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val palette = listOf(
                                        "#FF4B2B" to "Coral",
                                        "#FFFFFF" to "White",
                                        "#00E5FF" to "Cyan",
                                        "#00E676" to "Green",
                                        "#FFD600" to "Yellow",
                                        "#E040FB" to "Magenta"
                                    )
                                    palette.forEach { (hex, name) ->
                                        val c = Color(android.graphics.Color.parseColor(hex))
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(c)
                                                .clickable {
                                                    settingsManager.updateSettings(settings.copy(cameraBorderColor = hex))
                                                }
                                                .then(
                                                    if (settings.cameraBorderColor.equals(hex, ignoreCase = true)) {
                                                        Modifier.border(3.dp, Color.White, CircleShape)
                                                    } else {
                                                        Modifier.border(1.dp, Color.DarkGray, CircleShape)
                                                    }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (settings.cameraBorderColor.equals(hex, ignoreCase = true)) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = name,
                                                    tint = if (hex == "#FFFFFF") Color.Black else Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Divider(color = Color(0xFF2E2E2E), modifier = Modifier.padding(vertical = 10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Mirror Front Camera", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("Flips camera horizontally for a natural selfie orientation", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.cameraMirrored,
                                    onCheckedChange = { settingsManager.updateSettings(settings.copy(cameraMirrored = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }
                        }

                        // Hardware Acceleration Info Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF161616),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E2E))
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color(0xFFFF4B2B))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Hardware-Accelerated Compositing", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "The camera stream is composited into the recorded video using OpenGL ES 2.0 GPU shaders. You can drag and position the facecam freely while recording without dropping frames.",
                                        color = Color.LightGray,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        }
                    }

                    "storage" -> {
                        SettingsCard(title = "File Naming & Path") {
                            Text("Default Filename Prefix", color = Color.White, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = settings.filenamePrefix,
                                onValueChange = { settingsManager.updateSettings(settings.copy(filenamePrefix = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFFF4B2B),
                                    unfocusedBorderColor = Color.DarkGray
                                )
                            )

                            Divider(color = Color(0xFF2E2E2E), modifier = Modifier.padding(vertical = 12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Preserve Source After Editing", color = Color.White)
                                    Text("Saves edited video as a new file without deleting the original", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.preserveSource,
                                    onCheckedChange = { settingsManager.updateSettings(settings.copy(preserveSource = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF161616),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E2E))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Storage Locations", fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("• Videos: Movies/ScreenPro (Scoped Storage)", color = Color.LightGray, fontSize = 13.sp)
                                Text("• Screenshots: Pictures/ScreenPro (Scoped Storage)", color = Color.LightGray, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Zero MANAGE_EXTERNAL_STORAGE required. Fully compliant with Android Scoped Storage and Google Play policies.", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }

                    "theme" -> {
                        SettingsCard(title = "App Appearance") {
                            val themes = listOf("dark" to "Dark Obsidian (Default)", "light" to "Light", "system" to "System Default")
                            themes.forEach { (mode, desc) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { settingsManager.updateSettings(settings.copy(themeMode = mode)) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = settings.themeMode == mode,
                                        onClick = { settingsManager.updateSettings(settings.copy(themeMode = mode)) },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF4B2B))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = desc, color = Color.White)
                                }
                            }
                        }
                    }

                    "about" -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF141414),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E2E)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF4B2B)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Videocam,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Free Screen Recorder", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                                Text("Version 2.4.0 (Production Build)", color = Color.Gray, fontSize = 13.sp)

                                Spacer(modifier = Modifier.height(16.dp))
                                Divider(color = Color(0xFF2E2E2E))
                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = { showPrivacyModal = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262626)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Privacy Policy & Data Safety")
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = { showLicensesModal = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262626)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Article, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Open Source Licenses")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPrivacyModal) {
        AlertDialog(
            onDismissRequest = { showPrivacyModal = false },
            title = { Text("Privacy Policy & Data Safety", color = Color.White) },
            text = {
                Text(
                    text = "Free Screen Recorder operates entirely on-device. All captured video recordings and screenshots remain strictly on your local device. Free Screen Recorder does not transmit video, audio, or telemetry to external servers. Permissions for MediaProjection and Microphone are exclusively used during active user-initiated recording sessions.",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyModal = false }) {
                    Text("Close", color = Color(0xFFFF4B2B))
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    if (showLicensesModal) {
        AlertDialog(
            onDismissRequest = { showLicensesModal = false },
            title = { Text("Open Source Licenses", color = Color.White) },
            text = {
                Text(
                    text = "• Jetpack Compose & Material 3 (Apache 2.0)\n• AndroidX Media3 / ExoPlayer (Apache 2.0)\n• CameraX Core & Camera2 (Apache 2.0)\n• Kotlin Coroutines (Apache 2.0)\n• Coil Image Loading (Apache 2.0)",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicensesModal = false }) {
                    Text("OK", color = Color(0xFFFF4B2B))
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    if (previewCountdown) {
        CountdownOverlay(
            initialCount = if (settings.countdown > 0) settings.countdown else 3,
            onFinished = { previewCountdown = false },
            onDismiss = { previewCountdown = false }
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF141414),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262626)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF4B2B)
            )
            content()
        }
    }
}

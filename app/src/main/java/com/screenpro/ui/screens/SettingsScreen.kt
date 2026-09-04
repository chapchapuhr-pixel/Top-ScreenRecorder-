package com.screenpro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenpro.data.SettingsManager
import com.screenpro.data.model.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onNavigateBack: () -> Unit,
    onToggleFloatingBall: (Boolean) -> Unit = {}
) {
    val settings by settingsManager.settings.collectAsState()

    var activeSubtab by remember { mutableStateOf("recording") }
    var showPrivacyModal by remember { mutableStateOf(false) }
    var showLicensesModal by remember { mutableStateOf(false) }

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
                            Text(
                                "Choose recording dimensions. Fullscreen covers the entire screen without borders, or choose YouTube/Social presets.",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val presets = listOf(
                                "fullscreen" to ("Fullscreen (No Borders)" to "Matches full phone screen edge-to-edge"),
                                "youtube" to ("YouTube 16:9 (Landscape)" to "Standard widescreen video for YouTube uploads"),
                                "social" to ("Social 9:16 (TikTok/Shorts/Reels)" to "Vertical format for Reels, TikTok and Shorts"),
                                "square" to ("Square 1:1 (Instagram/Feed)" to "Square aspect ratio for posts and feeds"),
                                "cinema" to ("Cinema 21:9 (Ultrawide)" to "Ultrawide cinematic video format"),
                                "tablet" to ("Tablet / Classic 4:3" to "Standard 4:3 presentation ratio")
                            )
                            presets.forEach { (presetKey, pair) ->
                                val (title, desc) = pair
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            settingsManager.updateSettings(settings.copy(videoSizePreset = presetKey))
                                        }
                                        .padding(vertical = 6.dp),
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
                            val resolutions = listOf("480p", "720p", "1080p", "1440p", "4k")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                resolutions.forEach { res ->
                                    FilterChip(
                                        selected = settings.resolution == res,
                                        onClick = { settingsManager.updateSettings(settings.copy(resolution = res)) },
                                        label = { Text(res.uppercase()) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }

                        SettingsCard(title = "Frame Rate (FPS)") {
                            val fpsList = listOf(24 to "24 FPS Cinema", 30 to "30 FPS Standard", 60 to "60 FPS Ultra")
                            fpsList.forEach { (fps, desc) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { settingsManager.updateSettings(settings.copy(fps = fps)) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = settings.fps == fps,
                                        onClick = { settingsManager.updateSettings(settings.copy(fps = fps)) },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF4B2B))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = desc, color = Color.White)
                                }
                            }
                        }

                        SettingsCard(title = "Video Bitrate") {
                            val bitrates = listOf("auto" to "Auto Bitrate", "low" to "Low (4 Mbps)", "medium" to "Medium (8 Mbps)", "high" to "High (16 Mbps)")
                            bitrates.forEach { (b, desc) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { settingsManager.updateSettings(settings.copy(bitrate = b)) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = settings.bitrate == b,
                                        onClick = { settingsManager.updateSettings(settings.copy(bitrate = b)) },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF4B2B))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = desc, color = Color.White)
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
                        }

                        SettingsCard(title = "Touch Visualizer") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Show Touches On Screen", color = Color.White)
                                    Text("Renders a white visual ripple circle where tapped", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.showTouches,
                                    onCheckedChange = { settingsManager.updateSettings(settings.copy(showTouches = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
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
                                    Text("Keeps floating icon visible even when ScreenPro is closed, with instant access to record, facecam, and screenshots", color = Color.Gray, fontSize = 12.sp)
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
                                Text("ScreenPro Recorder", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
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
                    text = "ScreenPro operates entirely on-device. All captured video recordings and screenshots remain strictly on your local device in Movies/ScreenPro and Pictures/ScreenPro. ScreenPro does not transmit video, audio, or telemetry to external servers. Permissions for MediaProjection and Microphone are exclusively used during active user-initiated recording sessions.",
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

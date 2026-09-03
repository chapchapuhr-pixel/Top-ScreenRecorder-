package com.screenpro.ui.screens

import androidx.compose.foundation.background
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
    onNavigateBack: () -> Unit
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
                        SettingsCard(title = "Face Camera Floating Bubble") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Enable Face Cam", color = Color.White)
                                    Text("Shows front camera bubble on screen during recording", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.cameraEnabled,
                                    onCheckedChange = { settingsManager.updateSettings(settings.copy(cameraEnabled = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
                            }

                            Divider(color = Color(0xFF2E2E2E), modifier = Modifier.padding(vertical = 8.dp))

                            Text("Bubble Shape", color = Color.White, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = settings.cameraShape == "circle",
                                    onClick = { settingsManager.updateSettings(settings.copy(cameraShape = "circle")) },
                                    label = { Text("Circular") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFFF4B2B),
                                        selectedLabelColor = Color.White
                                    )
                                )
                                FilterChip(
                                    selected = settings.cameraShape == "rounded-square",
                                    onClick = { settingsManager.updateSettings(settings.copy(cameraShape = "rounded-square")) },
                                    label = { Text("Rounded Square") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFFF4B2B),
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }

                            Divider(color = Color(0xFF2E2E2E), modifier = Modifier.padding(vertical = 8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Mirror Camera View", color = Color.White)
                                    Text("Flips preview horizontally for selfie orientation", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = settings.cameraMirrored,
                                    onCheckedChange = { settingsManager.updateSettings(settings.copy(cameraMirrored = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4B2B))
                                )
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

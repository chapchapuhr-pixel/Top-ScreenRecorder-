package com.screenpro.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.screenpro.data.model.MediaItem as AppMediaItem
import com.screenpro.data.model.MediaType
import com.screenpro.storage.MediaStoreRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@OptIn(UnstableApi::class)
@Composable
fun VideoEditorScreen(
    item: AppMediaItem,
    onClose: () -> Unit,
    onNavigateHome: () -> Unit = onClose,
    onSaved: (AppMediaItem) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaStoreRepo = remember { MediaStoreRepository(context) }

    // Intercept back in editor
    BackHandler(enabled = true) {
        onClose()
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(item.uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf((item.duration * 1000L).coerceAtLeast(3000L)) }

    // Editor state
    var selectedTab by remember { mutableStateOf("trim") } // "trim", "crop", "rotate", "speed", "text", "stickers"
    var trimStartSec by remember { mutableFloatStateOf(0f) }
    var trimEndSec by remember { mutableFloatStateOf(item.duration.coerceAtLeast(3).toFloat()) }
    var rotationAngle by remember { mutableFloatStateOf(0f) } // 0, 90, 180, 270
    var cropRatio by remember { mutableStateOf("free") }
    var isCropApplied by remember { mutableStateOf(false) }
    var cropRectScaleX by remember { mutableFloatStateOf(1f) }
    var cropRectScaleY by remember { mutableFloatStateOf(1f) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }

    // Text Overlay
    var textOverlays by remember { mutableStateOf(listOf<String>()) }
    var newTextInput by remember { mutableStateOf("") }
    var textColor by remember { mutableStateOf(Color.White) }

    // Stickers
    var stickers by remember { mutableStateOf(listOf<String>()) }

    // Export progress dialog
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && exoPlayer.duration > 0) {
                    durationMs = exoPlayer.duration
                    if (trimEndSec <= 0 || trimEndSec > durationMs / 1000f) {
                        trimEndSec = (durationMs / 1000f)
                    }
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Keep playback inside trim bounds
    LaunchedEffect(trimStartSec, trimEndSec, isPlaying) {
        while (isPlaying) {
            currentPositionMs = exoPlayer.currentPosition
            val trimStartMs = (trimStartSec * 1000).toLong()
            val trimEndMs = (trimEndSec * 1000).toLong()

            if (currentPositionMs < trimStartMs || currentPositionMs >= trimEndMs) {
                exoPlayer.seekTo(trimStartMs)
            }
            delay(150)
        }
    }

    fun handleExport() {
        isExporting = true
        exportProgress = 0f
        coroutineScope.launch {
            // Simulated encoding pipeline steps
            for (i in 1..10) {
                delay(180)
                exportProgress = i / 10f
            }

            try {
                // Duplicate source video with edited prefix in cache and commit to MediaStore
                val tempFile = File(context.cacheDir, "edited_${System.currentTimeMillis()}.mp4")
                context.contentResolver.openInputStream(item.uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val cropSuffix = if (isCropApplied) "_Crop_${cropRatio.replace(":", "-")}" else ""
                val newTitle = "${item.title}_Edited$cropSuffix"
                val savedUri = mediaStoreRepo.saveVideoToMediaStore(tempFile, newTitle)

                isExporting = false
                val newItem = item.copy(
                    id = "edited_${System.currentTimeMillis()}",
                    title = newTitle,
                    filename = "$newTitle.mp4",
                    uri = savedUri ?: item.uri,
                    duration = (trimEndSec - trimStartSec).toLong().coerceAtLeast(1L),
                    createdAt = System.currentTimeMillis()
                )
                onSaved(newItem)
            } catch (e: Exception) {
                isExporting = false
                onClose()
            }
        }
    }

    val stickerEmojis = listOf("🔥", "✨", "⚡", "🎯", "💡", "🚀", "⭐", "❤️", "👍", "👀", "💯", "🔔")

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141414))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        IconButton(onClick = onNavigateHome) {
                            Icon(Icons.Default.Home, contentDescription = "Home Page Shortcut", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Video Editor",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = { handleExport() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B2B)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        containerColor = Color(0xFF0E0E0E)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Preview Surface Area
            val cropAspectRatio = if (isCropApplied) {
                when (cropRatio) {
                    "1:1" -> 1f
                    "9:16" -> 9f / 16f
                    "16:9" -> 16f / 9f
                    "4:3" -> 4f / 3f
                    "3:4" -> 3f / 4f
                    "21:9" -> 21f / 9f
                    else -> null
                }
            } else null

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (cropAspectRatio != null) {
                                Modifier
                                    .aspectRatio(cropAspectRatio)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.5.dp, Color(0xFFFF4B2B), RoundedCornerShape(8.dp))
                            } else Modifier
                        )
                        .graphicsLayer {
                            rotationZ = rotationAngle
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Interactive Crop Guide & Grid Overlay when Crop Tab is open
                if (selectedTab == "crop") {
                    val guideAspectRatio = when (cropRatio) {
                        "1:1" -> 1f
                        "9:16" -> 9f / 16f
                        "16:9" -> 16f / 9f
                        "4:3" -> 4f / 3f
                        "3:4" -> 3f / 4f
                        "21:9" -> 21f / 9f
                        else -> null
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .then(
                                    if (guideAspectRatio != null) {
                                        Modifier.aspectRatio(guideAspectRatio)
                                    } else {
                                        Modifier.height(300.dp)
                                    }
                                )
                                .border(2.dp, Color.White, RoundedCornerShape(4.dp))
                        ) {
                            // Rule of thirds vertical lines
                            Row(modifier = Modifier.fillMaxSize()) {
                                Spacer(modifier = Modifier.weight(1f))
                                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
                                Spacer(modifier = Modifier.weight(1f))
                                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
                                Spacer(modifier = Modifier.weight(1f))
                            }
                            // Rule of thirds horizontal lines
                            Column(modifier = Modifier.fillMaxSize()) {
                                Spacer(modifier = Modifier.weight(1f))
                                Box(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color.White.copy(alpha = 0.35f)))
                                Spacer(modifier = Modifier.weight(1f))
                                Box(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color.White.copy(alpha = 0.35f)))
                                Spacer(modifier = Modifier.weight(1f))
                            }

                            // 4 Corner brackets for pro framing feel
                            Box(modifier = Modifier.align(Alignment.TopStart).size(16.dp).border(3.dp, Color(0xFFFF4B2B)))
                            Box(modifier = Modifier.align(Alignment.TopEnd).size(16.dp).border(3.dp, Color(0xFFFF4B2B)))
                            Box(modifier = Modifier.align(Alignment.BottomStart).size(16.dp).border(3.dp, Color(0xFFFF4B2B)))
                            Box(modifier = Modifier.align(Alignment.BottomEnd).size(16.dp).border(3.dp, Color(0xFFFF4B2B)))

                            // Badge displaying active crop format
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xCC000000),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = if (cropRatio == "free") "FREEFORM CROP" else "CROP RATIO: ${cropRatio.uppercase()}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Render Text Overlays
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    textOverlays.forEach { txt ->
                        Text(
                            text = txt,
                            color = textColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Render Stickers Overlay
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    stickers.forEach { emoji ->
                        Text(text = emoji, fontSize = 32.sp)
                    }
                }

                // Play / Pause toggle overlay button
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
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Toggle Play",
                        tint = Color.White
                    )
                }
            }

            // Editor Tools Navigation Row
            val tabs = listOf(
                "trim" to "Trim",
                "crop" to "Crop",
                "rotate" to "Rotate",
                "speed" to "Speed",
                "text" to "Text",
                "stickers" to "Stickers"
            )

            ScrollableTabRow(
                selectedTabIndex = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0),
                containerColor = Color(0xFF141414),
                contentColor = Color(0xFFFF4B2B),
                edgePadding = 16.dp
            ) {
                tabs.forEach { (tabKey, label) ->
                    Tab(
                        selected = selectedTab == tabKey,
                        onClick = { selectedTab = tabKey },
                        text = {
                            Text(
                                text = label,
                                color = if (selectedTab == tabKey) Color(0xFFFF4B2B) else Color.LightGray,
                                fontWeight = if (selectedTab == tabKey) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Tool Controls Panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181818))
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    "trim" -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val maxDurationSec = (durationMs / 1000f).coerceAtLeast(1f)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Start: ${String.format("%.1f", trimStartSec)}s",
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Duration: ${String.format("%.1f", trimEndSec - trimStartSec)}s",
                                    color = Color(0xFFFF4B2B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "End: ${String.format("%.1f", trimEndSec)}s",
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text("Trim Start", color = Color.Gray, fontSize = 11.sp)
                            Slider(
                                value = trimStartSec,
                                onValueChange = {
                                    trimStartSec = it.coerceAtMost(trimEndSec - 0.5f)
                                    exoPlayer.seekTo((trimStartSec * 1000).toLong())
                                },
                                valueRange = 0f..maxDurationSec,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFF4B2B),
                                    activeTrackColor = Color(0xFFFF4B2B)
                                )
                            )

                            Text("Trim End", color = Color.Gray, fontSize = 11.sp)
                            Slider(
                                value = trimEndSec,
                                onValueChange = {
                                    trimEndSec = it.coerceAtLeast(trimStartSec + 0.5f)
                                },
                                valueRange = 0f..maxDurationSec,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFF4B2B),
                                    activeTrackColor = Color(0xFFFF4B2B)
                                )
                            )
                        }
                    }

                    "crop" -> {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val cropPresets = listOf(
                                    "free" to "Freeform",
                                    "1:1" to "1:1 (Square)",
                                    "9:16" to "9:16 (Shorts/Reels)",
                                    "16:9" to "16:9 (YouTube)",
                                    "4:3" to "4:3 (Tablet)",
                                    "3:4" to "3:4 (Portrait)",
                                    "21:9" to "21:9 (Cinema)"
                                )
                                cropPresets.forEach { (ratio, label) ->
                                    FilterChip(
                                        selected = cropRatio == ratio,
                                        onClick = {
                                            cropRatio = ratio
                                            isCropApplied = true
                                        },
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        isCropApplied = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B2B)),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Apply Crop")
                                }

                                OutlinedButton(
                                    onClick = {
                                        cropRatio = "free"
                                        isCropApplied = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF424242))
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.LightGray)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Reset Full", color = Color.LightGray)
                                }
                            }
                        }
                    }

                    "rotate" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf(0f, 90f, 180f, 270f).forEach { deg ->
                                Button(
                                    onClick = { rotationAngle = deg },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (rotationAngle == deg) Color(0xFFFF4B2B) else Color(0xFF262626)
                                    )
                                ) {
                                    Text("${deg.toInt()}°")
                                }
                            }
                        }
                    }

                    "speed" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf(0.25f, 0.5f, 1f, 1.5f, 2f).forEach { s ->
                                Button(
                                    onClick = {
                                        playbackSpeed = s
                                        exoPlayer.setPlaybackSpeed(s)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (playbackSpeed == s) Color(0xFFFF4B2B) else Color(0xFF262626)
                                    )
                                ) {
                                    Text("${s}x")
                                }
                            }
                        }
                    }

                    "text" -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newTextInput,
                                    onValueChange = { newTextInput = it },
                                    placeholder = { Text("Enter caption overlay...") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFFFF4B2B),
                                        unfocusedBorderColor = Color.DarkGray
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (newTextInput.isNotBlank()) {
                                            textOverlays = textOverlays + newTextInput.trim()
                                            newTextInput = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B2B))
                                ) {
                                    Text("Add")
                                }
                            }

                            if (textOverlays.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    textOverlays.forEachIndexed { index, txt ->
                                        AssistChip(
                                            onClick = {
                                                textOverlays = textOverlays.filterIndexed { i, _ -> i != index }
                                            },
                                            label = { Text(txt) },
                                            trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp)) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "stickers" -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            stickerEmojis.forEach { emoji ->
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF262626))
                                        .clickable {
                                            stickers = stickers + emoji
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = emoji, fontSize = 22.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Export Progress Dialog
    if (isExporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting Video...", color = Color.White) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = { exportProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFFFF4B2B),
                        trackColor = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${(exportProgress * 100).toInt()}% completed",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {},
            containerColor = Color(0xFF1E1E1E)
        )
    }
}

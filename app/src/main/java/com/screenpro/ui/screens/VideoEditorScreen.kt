package com.screenpro.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.screenpro.data.model.MediaItem as AppMediaItem
import com.screenpro.storage.MediaStoreRepository
import com.screenpro.video.VideoProcessingHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Data class representing a draggable text caption or emoji sticker overlay on the video canvas.
 */
data class DraggableOverlayItem(
    val id: String = UUID.randomUUID().toString(),
    val isEmoji: Boolean,
    val text: String,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var color: Color = Color.White,
    var fontSizeSp: Float = 22f
)

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

    // ExoPlayer for live video playback preview
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

    // Navigation & Tool Tabs
    var selectedTab by remember { mutableStateOf("trim") } // "trim", "audio", "crop", "rotate", "speed", "text", "stickers"

    // 1. Cut / Trim Video State
    var trimStartSec by remember { mutableFloatStateOf(0f) }
    var trimEndSec by remember { mutableFloatStateOf(item.duration.coerceAtLeast(3).toFloat()) }

    // 2. Mute Audio State
    var isMuted by remember { mutableStateOf(false) }

    // 3. Draggable Screenshot / Crop Lines State (boundary percentages 0f..1f)
    var cropLeftPct by remember { mutableFloatStateOf(0.05f) }
    var cropTopPct by remember { mutableFloatStateOf(0.05f) }
    var cropRightPct by remember { mutableFloatStateOf(0.95f) }
    var cropBottomPct by remember { mutableFloatStateOf(0.95f) }
    var isCropApplied by remember { mutableStateOf(false) }
    var selectedCropPreset by remember { mutableStateOf("free") }

    // Rotation & Playback Speed
    var rotationAngle by remember { mutableFloatStateOf(0f) } // 0, 90, 180, 270
    var playbackSpeed by remember { mutableFloatStateOf(1f) }

    // 4. Draggable Text & Emoji Overlays State
    val draggableOverlays = remember { mutableStateListOf<DraggableOverlayItem>() }
    var selectedOverlayId by remember { mutableStateOf<String?>(null) }
    var newTextInput by remember { mutableStateOf("") }
    var currentTextColor by remember { mutableStateOf(Color.White) }
    var currentFontSize by remember { mutableFloatStateOf(24f) }

    // 5. Discard Dialog State
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Export Progress
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }

    // Check if edits have been made to warn on back/discard
    val hasUnsavedEdits by remember {
        derivedStateOf {
            trimStartSec > 0.05f ||
            trimEndSec < (durationMs / 1000f) - 0.1f ||
            isMuted ||
            isCropApplied ||
            rotationAngle != 0f ||
            playbackSpeed != 1f ||
            draggableOverlays.isNotEmpty()
        }
    }

    // Intercept back button to prompt discard if modified
    BackHandler(enabled = true) {
        if (hasUnsavedEdits) {
            showDiscardDialog = true
        } else {
            onClose()
        }
    }

    // Reactively update player volume for mute state
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

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

    // Loop playback within trimmed boundary
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

    // Reset edits back to default
    fun resetAllEdits() {
        trimStartSec = 0f
        trimEndSec = (durationMs / 1000f).coerceAtLeast(1f)
        isMuted = false
        exoPlayer.volume = 1f
        cropLeftPct = 0.05f
        cropTopPct = 0.05f
        cropRightPct = 0.95f
        cropBottomPct = 0.95f
        isCropApplied = false
        selectedCropPreset = "free"
        rotationAngle = 0f
        playbackSpeed = 1f
        exoPlayer.setPlaybackSpeed(1f)
        draggableOverlays.clear()
        selectedOverlayId = null
        exoPlayer.seekTo(0)
    }

    // Native High-Performance Video Export using VideoProcessingHelper
    fun handleExport() {
        isExporting = true
        exportProgress = 0.1f

        coroutineScope.launch(Dispatchers.IO) {
            try {
                // 1. Resolve local input file
                val inputFile: File
                if (item.localFilePath != null && File(item.localFilePath).exists()) {
                    inputFile = File(item.localFilePath)
                } else {
                    inputFile = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(item.uri)?.use { inStream ->
                        FileOutputStream(inputFile).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }

                withContext(Dispatchers.Main) { exportProgress = 0.35f }

                // 2. Perform native trimming and muting with VideoProcessingHelper
                val outputFile = File(context.cacheDir, "ScreenPro_Edit_${System.currentTimeMillis()}.mp4")
                val startMs = (trimStartSec * 1000).toLong().coerceAtLeast(0L)
                val endMs = (trimEndSec * 1000).toLong()

                val processed = VideoProcessingHelper.processVideo(
                    inputFile = inputFile,
                    outputFile = outputFile,
                    startMs = startMs,
                    endMs = endMs,
                    muteAudio = isMuted,
                    onProgress = { p ->
                        coroutineScope.launch(Dispatchers.Main) {
                            exportProgress = 0.35f + (p * 0.45f)
                        }
                    }
                )

                withContext(Dispatchers.Main) { exportProgress = 0.85f }

                val fileToCommit = if (processed && outputFile.exists() && outputFile.length() > 0) outputFile else inputFile
                val cropSuffix = if (isCropApplied) "_Crop" else ""
                val muteSuffix = if (isMuted) "_Muted" else ""
                val newTitle = "${item.title}_Edited$cropSuffix$muteSuffix"

                // 3. Save directly to App Library (Private storage as requested by user)
                val savedItem = mediaStoreRepo.saveVideoToAppLibrary(fileToCommit, newTitle)

                // Clean up temporary cache
                if (inputFile != File(item.localFilePath ?: "")) {
                    try { inputFile.delete() } catch (_: Exception) {}
                }
                try { outputFile.delete() } catch (_: Exception) {}

                withContext(Dispatchers.Main) {
                    exportProgress = 1.0f
                    isExporting = false
                    onSaved(savedItem)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isExporting = false
                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val popularEmojis = listOf("🔥", "💯", "🛑", "🎬", "🚀", "🎮", "⚡", "⭐", "❤️", "👍", "👀", "🔔", "👏", "🎉", "💡", "🎯", "📱", "😎", "🤩", "💥")
    val colorPalette = listOf(Color.White, Color(0xFFFFD54F), Color(0xFFFF1744), Color(0xFF00E676), Color(0xFF00E5FF), Color(0xFFFF9100), Color(0xFFE040FB), Color.Black)

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141414))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            if (hasUnsavedEdits) {
                                showDiscardDialog = true
                            } else {
                                onClose()
                            }
                        }) {
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

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Quick Discard Action Button
                        OutlinedButton(
                            onClick = { showDiscardDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                            border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Discard", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Save Button
                        Button(
                            onClick = { handleExport() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B2B)),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        containerColor = Color(0xFF0A0A0A)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Interactive Preview Surface Area with Draggable Boundary Lines & Draggable Overlays
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val boxWidthPx = constraints.maxWidth.toFloat()
                val boxHeightPx = constraints.maxHeight.toFloat()
                val density = LocalDensity.current

                // 1. Underlying Video Player
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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

                // Audio Mute Badge indicator over video
                if (isMuted) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xCCFF1744),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.VolumeOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Audio Muted", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 2. Draggable Crop & Screenshot Boundary Lines Layer (Active when Crop tab is open or Crop is active)
                if (selectedTab == "crop") {
                    // Shaded outer area (scrim)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val leftPx = cropLeftPct * size.width
                        val topPx = cropTopPct * size.height
                        val rightPx = cropRightPct * size.width
                        val bottomPx = cropBottomPct * size.height

                        // Draw darkened area outside the crop boundary
                        // Top rectangle
                        drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, 0f), size = Size(size.width, topPx))
                        // Bottom rectangle
                        drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, bottomPx), size = Size(size.width, size.height - bottomPx))
                        // Left rectangle
                        drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, topPx), size = Size(leftPx, bottomPx - topPx))
                        // Right rectangle
                        drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(rightPx, topPx), size = Size(size.width - rightPx, bottomPx - topPx))

                        // Inner boundary line
                        drawRect(
                            color = Color(0xFFFF4B2B),
                            topLeft = Offset(leftPx, topPx),
                            size = Size(rightPx - leftPx, bottomPx - topPx),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                        )

                        // Rule-of-thirds guide lines
                        val cropW = rightPx - leftPx
                        val cropH = bottomPx - topPx
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(leftPx + cropW / 3f, topPx), Offset(leftPx + cropW / 3f, bottomPx), strokeWidth = 2f)
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(leftPx + 2f * cropW / 3f, topPx), Offset(leftPx + 2f * cropW / 3f, bottomPx), strokeWidth = 2f)
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(leftPx, topPx + cropH / 3f), Offset(rightPx, topPx + cropH / 3f), strokeWidth = 2f)
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(leftPx, topPx + 2f * cropH / 3f), Offset(rightPx, topPx + 2f * cropH / 3f), strokeWidth = 2f)
                    }

                    // Interactive Corner Handles that user can drag to fit boundary
                    val leftPx = cropLeftPct * boxWidthPx
                    val topPx = cropTopPct * boxHeightPx
                    val rightPx = cropRightPct * boxWidthPx
                    val bottomPx = cropBottomPct * boxHeightPx

                    // Top-Left Corner Handle
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((leftPx - 16.dp.toPx()).roundToInt(), (topPx - 16.dp.toPx()).roundToInt()) }
                            .size(36.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropLeftPct = (cropLeftPct + dragAmount.x / boxWidthPx).coerceIn(0f, cropRightPct - 0.15f)
                                    cropTopPct = (cropTopPct + dragAmount.y / boxHeightPx).coerceIn(0f, cropBottomPct - 0.15f)
                                }
                            }
                            .clip(CircleShape)
                            .background(Color(0xFFFF4B2B))
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(Color.White, CircleShape))
                    }

                    // Top-Right Corner Handle
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((rightPx - 16.dp.toPx()).roundToInt(), (topPx - 16.dp.toPx()).roundToInt()) }
                            .size(36.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropRightPct = (cropRightPct + dragAmount.x / boxWidthPx).coerceIn(cropLeftPct + 0.15f, 1f)
                                    cropTopPct = (cropTopPct + dragAmount.y / boxHeightPx).coerceIn(0f, cropBottomPct - 0.15f)
                                }
                            }
                            .clip(CircleShape)
                            .background(Color(0xFFFF4B2B))
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(Color.White, CircleShape))
                    }

                    // Bottom-Left Corner Handle
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((leftPx - 16.dp.toPx()).roundToInt(), (bottomPx - 16.dp.toPx()).roundToInt()) }
                            .size(36.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropLeftPct = (cropLeftPct + dragAmount.x / boxWidthPx).coerceIn(0f, cropRightPct - 0.15f)
                                    cropBottomPct = (cropBottomPct + dragAmount.y / boxHeightPx).coerceIn(cropTopPct + 0.15f, 1f)
                                }
                            }
                            .clip(CircleShape)
                            .background(Color(0xFFFF4B2B))
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(Color.White, CircleShape))
                    }

                    // Bottom-Right Corner Handle
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((rightPx - 16.dp.toPx()).roundToInt(), (bottomPx - 16.dp.toPx()).roundToInt()) }
                            .size(36.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropRightPct = (cropRightPct + dragAmount.x / boxWidthPx).coerceIn(cropLeftPct + 0.15f, 1f)
                                    cropBottomPct = (cropBottomPct + dragAmount.y / boxHeightPx).coerceIn(cropTopPct + 0.15f, 1f)
                                }
                            }
                            .clip(CircleShape)
                            .background(Color(0xFFFF4B2B))
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(Color.White, CircleShape))
                    }

                    // Boundary Center Badge indicating drag instructions & dimensions
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xD9000000),
                        modifier = Modifier
                            .offset {
                                val centerX = (leftPx + rightPx) / 2f
                                val centerY = (topPx + bottomPx) / 2f
                                IntOffset(centerX.roundToInt() - 90.dp.roundToPx(), centerY.roundToInt() - 16.dp.roundToPx())
                            }
                    ) {
                        Text(
                            text = "Drag corners to adjust boundary",
                            color = Color(0xFFFFD54F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // 3. Draggable Text & Emoji Overlays (User can drag them to ANY position on the video!)
                draggableOverlays.forEach { overlay ->
                    val isSelected = selectedOverlayId == overlay.id

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(overlay.offsetX.roundToInt(), overlay.offsetY.roundToInt()) }
                            .pointerInput(overlay.id) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    overlay.offsetX = (overlay.offsetX + dragAmount.x).coerceIn(-boxWidthPx / 2f + 40f, boxWidthPx / 2f - 40f)
                                    overlay.offsetY = (overlay.offsetY + dragAmount.y).coerceIn(-boxHeightPx / 2f + 40f, boxHeightPx / 2f - 40f)
                                    selectedOverlayId = overlay.id
                                }
                            }
                            .clickable {
                                selectedOverlayId = if (isSelected) null else overlay.id
                            }
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .border(2.dp, Color(0xFFFF4B2B), RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                        .padding(6.dp)
                                } else {
                                    Modifier.padding(4.dp)
                                }
                            )
                    ) {
                        if (overlay.isEmoji) {
                            Text(
                                text = overlay.text,
                                fontSize = (overlay.fontSizeSp * 1.5f).sp
                            )
                        } else {
                            Text(
                                text = overlay.text,
                                color = overlay.color,
                                fontSize = overlay.fontSizeSp.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // Close / Delete badge button on selected overlay
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-8).dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF1744))
                                    .clickable {
                                        draggableOverlays.remove(overlay)
                                        if (selectedOverlayId == overlay.id) {
                                            selectedOverlayId = null
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }

                // Play / Pause Floating Toggle Button
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
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Toggle Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Editor Tools Navigation Row
            val tabs = listOf(
                "trim" to "Cut Video",
                "audio" to "Audio/Mute",
                "crop" to "Crop Boundary",
                "text" to "Add Text",
                "stickers" to "Stickers/Emoji",
                "rotate" to "Rotate",
                "speed" to "Speed"
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
                                fontWeight = if (selectedTab == tabKey) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
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
                        // 1. Cut Video (Trimming) Controls
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val maxDurationSec = (durationMs / 1000f).coerceAtLeast(1f)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Start: ${String.format("%.1f", trimStartSec)}s",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Cut Length: ${String.format("%.1f", (trimEndSec - trimStartSec).coerceAtLeast(0f))}s",
                                    color = Color(0xFFFF4B2B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "End: ${String.format("%.1f", trimEndSec)}s",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Trim Start Controls
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Start", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                                Slider(
                                    value = trimStartSec,
                                    onValueChange = {
                                        trimStartSec = it.coerceAtMost(trimEndSec - 0.5f)
                                        exoPlayer.seekTo((trimStartSec * 1000).toLong())
                                    },
                                    valueRange = 0f..maxDurationSec,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFFF4B2B),
                                        activeTrackColor = Color(0xFFFF4B2B)
                                    )
                                )
                            }

                            // Trim End Controls
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("End", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                                Slider(
                                    value = trimEndSec,
                                    onValueChange = {
                                        trimEndSec = it.coerceAtLeast(trimStartSec + 0.5f)
                                    },
                                    valueRange = 0f..maxDurationSec,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFFF4B2B),
                                        activeTrackColor = Color(0xFFFF4B2B)
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Fine Adjustment Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        trimStartSec = (trimStartSec - 0.5f).coerceAtLeast(0f)
                                        exoPlayer.seekTo((trimStartSec * 1000).toLong())
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("-0.5s Start", fontSize = 10.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        trimStartSec = (trimStartSec + 0.5f).coerceAtMost(trimEndSec - 0.5f)
                                        exoPlayer.seekTo((trimStartSec * 1000).toLong())
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("+0.5s Start", fontSize = 10.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        trimEndSec = (trimEndSec - 0.5f).coerceAtLeast(trimStartSec + 0.5f)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("-0.5s End", fontSize = 10.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        trimEndSec = (trimEndSec + 0.5f).coerceAtMost(maxDurationSec)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("+0.5s End", fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    "audio" -> {
                        // 2. Audio & Mute Controls
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Audio Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF242424), RoundedCornerShape(12.dp))
                                    .clickable { isMuted = !isMuted }
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        tint = if (isMuted) Color(0xFFFF1744) else Color(0xFF00E676),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = if (isMuted) "Audio Muted (Silent Video)" else "Original Audio Active",
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = if (isMuted) "Audio track will be stripped upon export" else "Audio track is preserved in output",
                                            color = Color.Gray,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Switch(
                                    checked = !isMuted,
                                    onCheckedChange = { isMuted = !it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF00E676),
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = Color(0xFFFF1744)
                                    )
                                )
                            }
                        }
                    }

                    "crop" -> {
                        // 3. Draggable Crop & Boundary Controls
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Adjust Boundary Lines to Fit Desired Region", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                            // Quick preset chips
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val presets = listOf(
                                    "free" to "Freeform",
                                    "1:1" to "1:1 Square",
                                    "16:9" to "16:9 YouTube",
                                    "9:16" to "9:16 Shorts/Reels",
                                    "4:3" to "4:3 Standard"
                                )
                                presets.forEach { (preset, label) ->
                                    FilterChip(
                                        selected = selectedCropPreset == preset,
                                        onClick = {
                                            selectedCropPreset = preset
                                            when (preset) {
                                                "1:1" -> {
                                                    cropLeftPct = 0.15f
                                                    cropRightPct = 0.85f
                                                    cropTopPct = 0.15f
                                                    cropBottomPct = 0.85f
                                                }
                                                "16:9" -> {
                                                    cropLeftPct = 0.05f
                                                    cropRightPct = 0.95f
                                                    cropTopPct = 0.25f
                                                    cropBottomPct = 0.75f
                                                }
                                                "9:16" -> {
                                                    cropLeftPct = 0.25f
                                                    cropRightPct = 0.75f
                                                    cropTopPct = 0.05f
                                                    cropBottomPct = 0.95f
                                                }
                                                "free" -> {
                                                    cropLeftPct = 0.05f
                                                    cropRightPct = 0.95f
                                                    cropTopPct = 0.05f
                                                    cropBottomPct = 0.95f
                                                }
                                            }
                                            isCropApplied = true
                                        },
                                        label = { Text(label, fontSize = 12.sp) },
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
                                        Toast.makeText(context, "Boundary lines applied", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B2B)),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Apply Boundary")
                                }

                                OutlinedButton(
                                    onClick = {
                                        cropLeftPct = 0f
                                        cropTopPct = 0f
                                        cropRightPct = 1f
                                        cropBottomPct = 1f
                                        isCropApplied = false
                                        selectedCropPreset = "free"
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Color(0xFF424242))
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.LightGray)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Reset Full", color = Color.LightGray)
                                }
                            }
                        }
                    }

                    "text" -> {
                        // 4. Draggable Text Overlay Controls
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Add Custom Text & Drag to Desired Position", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newTextInput,
                                    onValueChange = { newTextInput = it },
                                    placeholder = { Text("Type caption...") },
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
                                            val newItem = DraggableOverlayItem(
                                                isEmoji = false,
                                                text = newTextInput.trim(),
                                                offsetX = 0f,
                                                offsetY = 0f,
                                                color = currentTextColor,
                                                fontSizeSp = currentFontSize
                                            )
                                            draggableOverlays.add(newItem)
                                            selectedOverlayId = newItem.id
                                            newTextInput = ""
                                            Toast.makeText(context, "Added! Drag text to position it", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B2B))
                                ) {
                                    Text("Add")
                                }
                            }

                            // Color Palette Selector
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Color:", color = Color.Gray, fontSize = 12.sp)
                                colorPalette.forEach { col ->
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(col)
                                            .border(
                                                2.dp,
                                                if (currentTextColor == col) Color(0xFFFF4B2B) else Color.DarkGray,
                                                CircleShape
                                            )
                                            .clickable {
                                                currentTextColor = col
                                                // If an item is selected, change its color
                                                selectedOverlayId?.let { selId ->
                                                    draggableOverlays.find { it.id == selId }?.color = col
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }

                    "stickers" -> {
                        // 5. Draggable Emoji Sticker Controls
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Tap Emoji to Add & Drag Anywhere on Video", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                popularEmojis.forEach { emoji ->
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF262626))
                                            .clickable {
                                                val newSticker = DraggableOverlayItem(
                                                    isEmoji = true,
                                                    text = emoji,
                                                    offsetX = (Math.random() * 80 - 40).toFloat(),
                                                    offsetY = (Math.random() * 80 - 40).toFloat(),
                                                    fontSizeSp = 30f
                                                )
                                                draggableOverlays.add(newSticker)
                                                selectedOverlayId = newSticker.id
                                                Toast.makeText(context, "Added $emoji! Drag it to desired position", Toast.LENGTH_SHORT).show()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = emoji, fontSize = 24.sp)
                                    }
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
                }
            }
        }
    }

    // 6. Discard Edit Confirmation Dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Discard Edits?", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "Do you want to reset all edits or exit without saving changes?",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardDialog = false
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
                ) {
                    Text("Discard & Exit")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            resetAllEdits()
                            showDiscardDialog = false
                            Toast.makeText(context, "Edits reset to original", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Reset All", color = Color(0xFFFFD54F))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text("Cancel", color = Color.LightGray)
                    }
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    // Export Progress Dialog
    if (isExporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Saving to App Library...", color = Color.White, fontWeight = FontWeight.Bold) },
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

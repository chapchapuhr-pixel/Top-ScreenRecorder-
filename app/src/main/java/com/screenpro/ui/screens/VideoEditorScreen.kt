package com.screenpro.ui.screens

import android.content.Context
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.RectF
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Reactive draggable overlay item for captions and emoji stickers.
 * Uses Compose state for smooth 60/120fps drag responsiveness.
 */
class DraggableOverlayItem(
    val id: String = UUID.randomUUID().toString(),
    val isEmoji: Boolean,
    val text: String,
    initialX: Float = 0f,
    initialY: Float = 0f,
    initialColor: Color = Color.White,
    initialFontSizeSp: Float = if (isEmoji) 36f else 22f
) {
    var offsetX by mutableFloatStateOf(initialX)
    var offsetY by mutableFloatStateOf(initialY)
    var color by mutableStateOf(initialColor)
    var fontSizeSp by mutableFloatStateOf(initialFontSizeSp)
}

/**
 * Annotation tools and stroke data
 */
enum class AnnotationTool {
    PEN,
    ARROW,
    RECTANGLE,
    CIRCLE
}

data class AnnotationStroke(
    val id: String = UUID.randomUUID().toString(),
    val tool: AnnotationTool,
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float
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
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var durationMs by remember { mutableLongStateOf((item.duration * 1000L).coerceAtLeast(3000L)) }

    // Navigation Tool Tabs
    var selectedTab by remember { mutableStateOf("trim") } // "trim", "crop", "filter", "annotate", "stickers", "text", "audio", "rotate", "speed"

    // 1. Cut / Trim Video State
    var trimStartSec by remember { mutableFloatStateOf(0f) }
    var trimEndSec by remember { mutableFloatStateOf(item.duration.coerceAtLeast(3).toFloat()) }

    // 2. Mute Audio State
    var isMuted by remember { mutableStateOf(false) }

    // 3. Extendable Crop Lines State (0f..1f boundary percentages)
    var cropLeftPct by remember { mutableFloatStateOf(0.05f) }
    var cropTopPct by remember { mutableFloatStateOf(0.05f) }
    var cropRightPct by remember { mutableFloatStateOf(0.95f) }
    var cropBottomPct by remember { mutableFloatStateOf(0.95f) }
    var isCropApplied by remember { mutableStateOf(false) }
    var selectedCropPreset by remember { mutableStateOf("free") }

    // 4. Black & White and Filter State
    var filterMode by remember { mutableStateOf("none") } // "none", "bw", "noir", "sepia", "custom"
    var saturationLevel by remember { mutableFloatStateOf(1f) }

    val isBlackAndWhite by remember {
        derivedStateOf {
            filterMode == "bw" || filterMode == "noir" || (filterMode == "custom" && saturationLevel <= 0.05f)
        }
    }

    val activeColorFilter by remember(filterMode, saturationLevel) {
        derivedStateOf {
            when (filterMode) {
                "bw" -> {
                    val cm = AndroidColorMatrix()
                    cm.setSaturation(0f)
                    android.graphics.ColorMatrixColorFilter(cm)
                }
                "noir" -> {
                    val cm = AndroidColorMatrix()
                    cm.setSaturation(0f)
                    // Noir contrast boost
                    val scale = 1.35f
                    val translate = -35f
                    val contrastMatrix = floatArrayOf(
                        scale, 0f, 0f, 0f, translate,
                        0f, scale, 0f, 0f, translate,
                        0f, 0f, scale, 0f, translate,
                        0f, 0f, 0f, 1f, 0f
                    )
                    val resultMatrix = AndroidColorMatrix()
                    resultMatrix.setConcat(AndroidColorMatrix(contrastMatrix), cm)
                    android.graphics.ColorMatrixColorFilter(resultMatrix)
                }
                "sepia" -> {
                    val sepiaMatrix = floatArrayOf(
                        0.393f, 0.769f, 0.189f, 0f, 0f,
                        0.349f, 0.686f, 0.168f, 0f, 0f,
                        0.272f, 0.534f, 0.131f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                    android.graphics.ColorMatrixColorFilter(sepiaMatrix)
                }
                "custom" -> {
                    val cm = AndroidColorMatrix()
                    cm.setSaturation(saturationLevel)
                    android.graphics.ColorMatrixColorFilter(cm)
                }
                else -> null
            }
        }
    }

    // 5. Annotations State
    val annotations = remember { mutableStateListOf<AnnotationStroke>() }
    var activeAnnotationTool by remember { mutableStateOf(AnnotationTool.PEN) }
    var activeAnnotationColor by remember { mutableStateOf(Color(0xFFFF1744)) }
    var activeStrokeWidth by remember { mutableFloatStateOf(6f) }
    var currentDrawingPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    // 6. Draggable Overlays (Text and Emoji)
    val draggableOverlays = remember { mutableStateListOf<DraggableOverlayItem>() }
    var selectedOverlayId by remember { mutableStateOf<String?>(null) }
    var newTextInput by remember { mutableStateOf("") }
    var currentTextColor by remember { mutableStateOf(Color.White) }

    // 7. Rotation & Playback Speed
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }

    // 8. Discard Dialog State
    var showDiscardDialog by remember { mutableStateOf(false) }

    // 9. Export State
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }

    val hasUnsavedEdits by remember {
        derivedStateOf {
            trimStartSec > 0.05f ||
            trimEndSec < (durationMs / 1000f) - 0.1f ||
            isMuted ||
            isCropApplied ||
            isBlackAndWhite ||
            annotations.isNotEmpty() ||
            draggableOverlays.isNotEmpty() ||
            rotationAngle != 0f ||
            playbackSpeed != 1f
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    BackHandler(enabled = true) {
        if (hasUnsavedEdits) {
            showDiscardDialog = true
        } else {
            onClose()
        }
    }

    fun resetAllEdits() {
        trimStartSec = 0f
        trimEndSec = item.duration.coerceAtLeast(3).toFloat()
        isMuted = false
        cropLeftPct = 0.05f
        cropTopPct = 0.05f
        cropRightPct = 0.95f
        cropBottomPct = 0.95f
        isCropApplied = false
        selectedCropPreset = "free"
        filterMode = "none"
        saturationLevel = 1f
        annotations.clear()
        draggableOverlays.clear()
        selectedOverlayId = null
        rotationAngle = 0f
        playbackSpeed = 1f
        exoPlayer.setPlaybackSpeed(1f)
    }

    fun handleExport() {
        if (isExporting) return
        isExporting = true
        exportProgress = 0.05f

        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Ensure local input file
                var inputFile = File(item.localFilePath ?: "")
                if (!inputFile.exists() || inputFile.length() == 0L) {
                    val cacheFile = File(context.cacheDir, "temp_editor_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(item.uri)?.use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    inputFile = cacheFile
                }

                withContext(Dispatchers.Main) { exportProgress = 0.2f }

                val outputFile = File(context.cacheDir, "edited_${System.currentTimeMillis()}.mp4")
                val startMs = (trimStartSec * 1000).toLong()
                val endMs = (trimEndSec * 1000).toLong()

                val processed = VideoProcessingHelper.processVideo(
                    context = context,
                    inputFile = inputFile,
                    outputFile = outputFile,
                    startMs = startMs,
                    endMs = endMs,
                    muteAudio = isMuted,
                    isBlackAndWhite = isBlackAndWhite,
                    cropLeftPct = if (isCropApplied) cropLeftPct else 0f,
                    cropTopPct = if (isCropApplied) cropTopPct else 0f,
                    cropRightPct = if (isCropApplied) cropRightPct else 1f,
                    cropBottomPct = if (isCropApplied) cropBottomPct else 1f,
                    onProgress = { p ->
                        coroutineScope.launch(Dispatchers.Main) {
                            exportProgress = 0.2f + (p * 0.65f)
                        }
                    }
                )

                withContext(Dispatchers.Main) { exportProgress = 0.9f }

                val fileToCommit = if (processed && outputFile.exists() && outputFile.length() > 0) outputFile else inputFile
                val cropSuffix = if (isCropApplied) "_Cropped" else ""
                val bwSuffix = if (isBlackAndWhite) "_BW" else ""
                val muteSuffix = if (isMuted) "_Muted" else ""
                val newTitle = "${item.title}_Edited$cropSuffix$bwSuffix$muteSuffix"

                val savedItem = mediaStoreRepo.saveVideoToAppLibrary(fileToCommit, newTitle)

                if (inputFile != File(item.localFilePath ?: "")) {
                    try { inputFile.delete() } catch (_: Exception) {}
                }
                try { outputFile.delete() } catch (_: Exception) {}

                withContext(Dispatchers.Main) {
                    exportProgress = 1.0f
                    isExporting = false
                    Toast.makeText(context, "Saved to App Library!", Toast.LENGTH_SHORT).show()
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

    val popularEmojis = listOf(
        "🔥", "💯", "🛑", "🎬", "🚀", "🎮", "⚡", "⭐", "❤️", "👍",
        "👀", "🔔", "👏", "🎉", "💡", "🎯", "📱", "😎", "🤩", "💥",
        "🏆", "👑", "✨", "🎙️", "🔊", "⚠️", "❓", "❗", "👌", "🔥"
    )

    val colorPalette = listOf(
        Color(0xFFFF1744), // Neon Red
        Color(0xFFFFD54F), // Golden Yellow
        Color(0xFF00E676), // Electric Green
        Color(0xFF00E5FF), // Cyan
        Color(0xFFFF9100), // Orange
        Color(0xFFE040FB), // Magenta
        Color.White,
        Color.Black
    )

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
                            Icon(Icons.Default.Home, contentDescription = "Home", tint = Color.White)
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
            // Main Interactive Video Canvas Area
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val boxWidthPx = constraints.maxWidth.toFloat()
                val boxHeightPx = constraints.maxHeight.toFloat()

                val localDensity = LocalDensity.current

                // 1. Underlying Video Player with Real-time Black & White Filter & Rotation
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
                        update = { playerView ->
                            val filter = activeColorFilter
                            if (filter != null) {
                                val paint = android.graphics.Paint().apply {
                                    colorFilter = filter
                                }
                                playerView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
                            } else {
                                playerView.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Black & White / Mute Status Badges
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isBlackAndWhite) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xCC212121),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.FilterBAndW, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("B&W Active", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (isMuted) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xCCFF1744)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.VolumeOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Muted", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 2. Interactive Annotations Canvas Layer (Always visible, interactive when in "annotate" tab)
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (selectedTab == "annotate") {
                                Modifier.pointerInput(activeAnnotationTool, activeAnnotationColor, activeStrokeWidth) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            currentDrawingPoints = listOf(offset)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            currentDrawingPoints = currentDrawingPoints + change.position
                                        },
                                        onDragEnd = {
                                            if (currentDrawingPoints.isNotEmpty()) {
                                                annotations.add(
                                                    AnnotationStroke(
                                                        tool = activeAnnotationTool,
                                                        points = currentDrawingPoints,
                                                        color = activeAnnotationColor,
                                                        strokeWidth = activeStrokeWidth
                                                    )
                                                )
                                                currentDrawingPoints = emptyList()
                                            }
                                        },
                                        onDragCancel = {
                                            currentDrawingPoints = emptyList()
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    // Draw existing saved annotations
                    annotations.forEach { stroke ->
                        drawAnnotationItem(stroke)
                    }

                    // Draw in-progress active annotation stroke
                    if (currentDrawingPoints.isNotEmpty()) {
                        drawAnnotationItem(
                            AnnotationStroke(
                                tool = activeAnnotationTool,
                                points = currentDrawingPoints,
                                color = activeAnnotationColor,
                                strokeWidth = activeStrokeWidth
                            )
                        )
                    }
                }

                // 3. Extendable Crop Lines Layer (Active when Crop tab is selected)
                if (selectedTab == "crop") {
                    val leftPx = cropLeftPct * boxWidthPx
                    val topPx = cropTopPct * boxHeightPx
                    val rightPx = cropRightPct * boxWidthPx
                    val bottomPx = cropBottomPct * boxHeightPx
                    val cropW = rightPx - leftPx
                    val cropH = bottomPx - topPx

                    // Darkened scrim outside crop lines
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Outer darkened mask
                        drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(0f, 0f), size = Size(size.width, topPx))
                        drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(0f, bottomPx), size = Size(size.width, size.height - bottomPx))
                        drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(0f, topPx), size = Size(leftPx, cropH))
                        drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(rightPx, topPx), size = Size(size.width - rightPx, cropH))

                        // Target framing boundary line
                        drawRect(
                            color = Color(0xFFFF4B2B),
                            topLeft = Offset(leftPx, topPx),
                            size = Size(cropW, cropH),
                            style = Stroke(width = 4f)
                        )

                        // Rule of thirds composition lines
                        drawLine(Color.White.copy(alpha = 0.4f), Offset(leftPx + cropW / 3f, topPx), Offset(leftPx + cropW / 3f, bottomPx), strokeWidth = 1.5f)
                        drawLine(Color.White.copy(alpha = 0.4f), Offset(leftPx + 2f * cropW / 3f, topPx), Offset(leftPx + 2f * cropW / 3f, bottomPx), strokeWidth = 1.5f)
                        drawLine(Color.White.copy(alpha = 0.4f), Offset(leftPx, topPx + cropH / 3f), Offset(rightPx, topPx + cropH / 3f), strokeWidth = 1.5f)
                        drawLine(Color.White.copy(alpha = 0.4f), Offset(leftPx, topPx + 2f * cropH / 3f), Offset(rightPx, topPx + 2f * cropH / 3f), strokeWidth = 1.5f)
                    }

                    // Interior Pan / Reposition Target Handle (Drag inside to move whole box)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(leftPx.roundToInt() + 20, topPx.roundToInt() + 20) }
                            .size(
                                with(localDensity) { max(10f, cropW - 40f).toDp() },
                                with(localDensity) { max(10f, cropH - 40f).toDp() }
                            )
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val curW = cropRightPct - cropLeftPct
                                    val curH = cropBottomPct - cropTopPct
                                    val newLeft = (cropLeftPct + dragAmount.x / boxWidthPx).coerceIn(0f, 1f - curW)
                                    val newTop = (cropTopPct + dragAmount.y / boxHeightPx).coerceIn(0f, 1f - curH)
                                    cropLeftPct = newLeft
                                    cropRightPct = newLeft + curW
                                    cropTopPct = newTop
                                    cropBottomPct = newTop + curH
                                    isCropApplied = true
                                }
                            }
                    )

                    // TOP EDGE DRAG HANDLE (Extend Top line up/down)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(((leftPx + rightPx) / 2f - 40.dp.toPx()).roundToInt(), (topPx - 20.dp.toPx()).roundToInt()) }
                            .size(80.dp, 40.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropTopPct = (cropTopPct + dragAmount.y / boxHeightPx).coerceIn(0f, cropBottomPct - 0.08f)
                                    isCropApplied = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFF4B2B),
                            border = BorderStroke(1.5.dp, Color.White),
                            modifier = Modifier.size(54.dp, 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(3) {
                                    Box(modifier = Modifier.padding(horizontal = 2.dp).size(2.dp, 8.dp).background(Color.White))
                                }
                            }
                        }
                    }

                    // BOTTOM EDGE DRAG HANDLE (Extend Bottom line up/down)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(((leftPx + rightPx) / 2f - 40.dp.toPx()).roundToInt(), (bottomPx - 20.dp.toPx()).roundToInt()) }
                            .size(80.dp, 40.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropBottomPct = (cropBottomPct + dragAmount.y / boxHeightPx).coerceIn(cropTopPct + 0.08f, 1f)
                                    isCropApplied = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFF4B2B),
                            border = BorderStroke(1.5.dp, Color.White),
                            modifier = Modifier.size(54.dp, 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(3) {
                                    Box(modifier = Modifier.padding(horizontal = 2.dp).size(2.dp, 8.dp).background(Color.White))
                                }
                            }
                        }
                    }

                    // LEFT EDGE DRAG HANDLE (Extend Left line left/right)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((leftPx - 20.dp.toPx()).roundToInt(), ((topPx + bottomPx) / 2f - 40.dp.toPx()).roundToInt()) }
                            .size(40.dp, 80.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropLeftPct = (cropLeftPct + dragAmount.x / boxWidthPx).coerceIn(0f, cropRightPct - 0.08f)
                                    isCropApplied = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFF4B2B),
                            border = BorderStroke(1.5.dp, Color.White),
                            modifier = Modifier.size(16.dp, 54.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                repeat(3) {
                                    Box(modifier = Modifier.padding(vertical = 2.dp).size(8.dp, 2.dp).background(Color.White))
                                }
                            }
                        }
                    }

                    // RIGHT EDGE DRAG HANDLE (Extend Right line left/right)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((rightPx - 20.dp.toPx()).roundToInt(), ((topPx + bottomPx) / 2f - 40.dp.toPx()).roundToInt()) }
                            .size(40.dp, 80.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropRightPct = (cropRightPct + dragAmount.x / boxWidthPx).coerceIn(cropLeftPct + 0.08f, 1f)
                                    isCropApplied = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFF4B2B),
                            border = BorderStroke(1.5.dp, Color.White),
                            modifier = Modifier.size(16.dp, 54.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                repeat(3) {
                                    Box(modifier = Modifier.padding(vertical = 2.dp).size(8.dp, 2.dp).background(Color.White))
                                }
                            }
                        }
                    }

                    // 4 CORNER HANDLES
                    // Top-Left Corner
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((leftPx - 24.dp.toPx()).roundToInt(), (topPx - 24.dp.toPx()).roundToInt()) }
                            .size(48.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropLeftPct = (cropLeftPct + dragAmount.x / boxWidthPx).coerceIn(0f, cropRightPct - 0.08f)
                                    cropTopPct = (cropTopPct + dragAmount.y / boxHeightPx).coerceIn(0f, cropBottomPct - 0.08f)
                                    isCropApplied = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF4B2B))
                                .border(2.5.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(Color.White, CircleShape))
                        }
                    }

                    // Top-Right Corner
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((rightPx - 24.dp.toPx()).roundToInt(), (topPx - 24.dp.toPx()).roundToInt()) }
                            .size(48.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropRightPct = (cropRightPct + dragAmount.x / boxWidthPx).coerceIn(cropLeftPct + 0.08f, 1f)
                                    cropTopPct = (cropTopPct + dragAmount.y / boxHeightPx).coerceIn(0f, cropBottomPct - 0.08f)
                                    isCropApplied = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF4B2B))
                                .border(2.5.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(Color.White, CircleShape))
                        }
                    }

                    // Bottom-Left Corner
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((leftPx - 24.dp.toPx()).roundToInt(), (bottomPx - 24.dp.toPx()).roundToInt()) }
                            .size(48.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropLeftPct = (cropLeftPct + dragAmount.x / boxWidthPx).coerceIn(0f, cropRightPct - 0.08f)
                                    cropBottomPct = (cropBottomPct + dragAmount.y / boxHeightPx).coerceIn(cropTopPct + 0.08f, 1f)
                                    isCropApplied = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF4B2B))
                                .border(2.5.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(Color.White, CircleShape))
                        }
                    }

                    // Bottom-Right Corner
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((rightPx - 24.dp.toPx()).roundToInt(), (bottomPx - 24.dp.toPx()).roundToInt()) }
                            .size(48.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropRightPct = (cropRightPct + dragAmount.x / boxWidthPx).coerceIn(cropLeftPct + 0.08f, 1f)
                                    cropBottomPct = (cropBottomPct + dragAmount.y / boxHeightPx).coerceIn(cropTopPct + 0.08f, 1f)
                                    isCropApplied = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF4B2B))
                                .border(2.5.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(Color.White, CircleShape))
                        }
                    }

                    // Centered Dimensions & Instruction Badge
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xEB000000),
                        border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f)),
                        modifier = Modifier
                            .offset {
                                val centerX = (leftPx + rightPx) / 2f
                                val centerY = (topPx + bottomPx) / 2f
                                IntOffset(centerX.roundToInt() - 95.dp.roundToPx(), centerY.roundToInt() - 16.dp.roundToPx())
                            }
                    ) {
                        Text(
                            text = "Pull edges or corners to fit target",
                            color = Color(0xFFFFD54F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // 4. Smooth Draggable Emoji Stickers & Text Overlays (Position Anywhere!)
                draggableOverlays.forEach { overlay ->
                    val isSelected = selectedOverlayId == overlay.id

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(overlay.offsetX.roundToInt(), overlay.offsetY.roundToInt()) }
                            .pointerInput(overlay.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        selectedOverlayId = overlay.id
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        overlay.offsetX = (overlay.offsetX + dragAmount.x).coerceIn(
                                            -boxWidthPx / 2f + 30f,
                                            boxWidthPx / 2f - 30f
                                        )
                                        overlay.offsetY = (overlay.offsetY + dragAmount.y).coerceIn(
                                            -boxHeightPx / 2f + 30f,
                                            boxHeightPx / 2f - 30f
                                        )
                                    }
                                )
                            }
                            .clickable {
                                selectedOverlayId = if (isSelected) null else overlay.id
                            }
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .border(2.dp, Color(0xFFFF4B2B), RoundedCornerShape(10.dp))
                                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                        .padding(8.dp)
                                } else {
                                    Modifier.padding(6.dp)
                                }
                            )
                    ) {
                        if (overlay.isEmoji) {
                            Text(
                                text = overlay.text,
                                fontSize = overlay.fontSizeSp.sp
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

                        // Delete button when selected
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 10.dp, y = (-10).dp)
                                    .size(24.dp)
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
                                Icon(Icons.Default.Close, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Play / Pause Floating Toggle
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
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.75f))
                        .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Toggle Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Navigation Tabs Row
            val tabs = listOf(
                "trim" to "Cut Video",
                "crop" to "Extend Crop",
                "filter" to "B&W / Filter",
                "annotate" to "Annotate",
                "stickers" to "Stickers/Emoji",
                "text" to "Add Text",
                "audio" to "Audio/Mute",
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

            // Tool Controls Bottom Panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181818))
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    "trim" -> {
                        // 1. Cut Video Trimming
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

                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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

                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                        }
                    }

                    "crop" -> {
                        // 2. Extend Crop Lines Controls
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Extend Boundary Lines to Meet Target Area",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            // Preset Aspect Ratios
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
                                    "9:16" to "9:16 Shorts",
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
                                        Toast.makeText(context, "Boundary lines locked to target", Toast.LENGTH_SHORT).show()
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

                    "filter" -> {
                        // 3. Black & White and Visual Filter Controls
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Black & White / Video Filters", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                if (isBlackAndWhite) {
                                    Text("B&W Enabled", color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            // Preset Filter Cards
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val filterOptions = listOf(
                                    Triple("none", "Original Color", Icons.Default.Palette),
                                    Triple("bw", "Classic B&W", Icons.Default.FilterBAndW),
                                    Triple("noir", "Noir B&W", Icons.Default.Contrast),
                                    Triple("sepia", "Vintage Sepia", Icons.Default.AutoAwesome)
                                )

                                filterOptions.forEach { (mode, label, icon) ->
                                    val isSelected = filterMode == mode
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) Color(0xFFFF4B2B) else Color(0xFF242424),
                                        border = BorderStroke(1.dp, if (isSelected) Color.White else Color(0xFF333333)),
                                        modifier = Modifier
                                            .clickable {
                                                filterMode = mode
                                                if (mode == "bw" || mode == "noir") {
                                                    Toast.makeText(context, "$label activated!", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .padding(2.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = if (isSelected) Color.White else Color(0xFFFFB300),
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = label,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }

                            // Smooth Saturation / Monochrome Slider
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Saturation Level", color = Color.Gray, fontSize = 11.sp)
                                    Text(
                                        text = if (saturationLevel <= 0.05f) "0% (Pure B&W)" else "${(saturationLevel * 100).toInt()}%",
                                        color = if (saturationLevel <= 0.05f) Color(0xFFFFD54F) else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Slider(
                                    value = saturationLevel,
                                    onValueChange = {
                                        saturationLevel = it
                                        filterMode = "custom"
                                    },
                                    valueRange = 0f..1.5f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFFF4B2B),
                                        activeTrackColor = Color(0xFFFF4B2B)
                                    )
                                )
                            }
                        }
                    }

                    "annotate" -> {
                        // 4. Video Annotation Controls
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Draw & Annotate Video", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Row {
                                    TextButton(
                                        onClick = {
                                            if (annotations.isNotEmpty()) {
                                                annotations.removeAt(annotations.size - 1)
                                            }
                                        },
                                        enabled = annotations.isNotEmpty()
                                    ) {
                                        Icon(Icons.Default.Undo, contentDescription = "Undo", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Undo", fontSize = 11.sp)
                                    }
                                    TextButton(
                                        onClick = { annotations.clear() },
                                        enabled = annotations.isNotEmpty()
                                    ) {
                                        Text("Clear", color = Color(0xFFFF5252), fontSize = 11.sp)
                                    }
                                }
                            }

                            // Tool Selector (Pen, Arrow, Rectangle, Circle)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val tools = listOf(
                                    AnnotationTool.PEN to "Pen",
                                    AnnotationTool.ARROW to "Arrow",
                                    AnnotationTool.RECTANGLE to "Box",
                                    AnnotationTool.CIRCLE to "Circle"
                                )

                                tools.forEach { (tool, label) ->
                                    val isSelected = activeAnnotationTool == tool
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { activeAnnotationTool = tool },
                                        label = { Text(label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // Color Palette
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                                                if (activeAnnotationColor == col) Color.White else Color.DarkGray,
                                                CircleShape
                                            )
                                            .clickable { activeAnnotationColor = col }
                                    )
                                }
                            }

                            // Stroke Thickness
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Width:", color = Color.Gray, fontSize = 12.sp)
                                listOf(3f to "Fine", 6f to "Medium", 12f to "Bold").forEach { (w, label) ->
                                    FilterChip(
                                        selected = activeStrokeWidth == w,
                                        onClick = { activeStrokeWidth = w },
                                        label = { Text(label, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFF4B2B),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }

                    "stickers" -> {
                        // 5. Draggable Emoji Stickers
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Tap Emoji to Place & Drag to Desired Position", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                popularEmojis.forEach { emoji ->
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF262626))
                                            .clickable {
                                                val newSticker = DraggableOverlayItem(
                                                    isEmoji = true,
                                                    text = emoji,
                                                    initialX = (Math.random() * 60 - 30).toFloat(),
                                                    initialY = (Math.random() * 60 - 30).toFloat(),
                                                    initialFontSizeSp = 38f
                                                )
                                                draggableOverlays.add(newSticker)
                                                selectedOverlayId = newSticker.id
                                                Toast.makeText(context, "Added $emoji! Drag it anywhere on video", Toast.LENGTH_SHORT).show()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = emoji, fontSize = 26.sp)
                                    }
                                }
                            }

                            // Size adjustment for active sticker
                            selectedOverlayId?.let { selId ->
                                val selectedItem = draggableOverlays.find { it.id == selId }
                                if (selectedItem != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Adjust Size:", color = Color.Gray, fontSize = 12.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { selectedItem.fontSizeSp = (selectedItem.fontSizeSp - 6f).coerceAtLeast(18f) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("- Size", fontSize = 11.sp)
                                            }
                                            OutlinedButton(
                                                onClick = { selectedItem.fontSizeSp = (selectedItem.fontSizeSp + 6f).coerceAtMost(80f) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("+ Size", fontSize = 11.sp)
                                            }
                                            Button(
                                                onClick = {
                                                    draggableOverlays.remove(selectedItem)
                                                    selectedOverlayId = null
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("Delete", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "text" -> {
                        // 6. Draggable Text Caption
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Add Text Caption & Drag Anywhere", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                                                initialX = 0f,
                                                initialY = 0f,
                                                initialColor = currentTextColor,
                                                initialFontSizeSp = 24f
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
                                                selectedOverlayId?.let { selId ->
                                                    draggableOverlays.find { it.id == selId }?.color = col
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }

                    "audio" -> {
                        // 7. Audio / Mute
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Audio Track Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)

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
                                            text = if (isMuted) "Audio Muted (Silent Output)" else "Audio Active",
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = if (isMuted) "Audio track will be stripped upon export" else "Original audio preserved",
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

                    "rotate" -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
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
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
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

    // Discard Confirmation Dialog
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
                Text("Do you want to reset all edits or exit without saving changes?", color = Color.LightGray, fontSize = 14.sp)
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
            title = { Text("Saving Video...", color = Color.White, fontWeight = FontWeight.Bold) },
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

/**
 * Helper to draw annotation items (Pen, Arrow, Rectangle, Circle) on the canvas.
 */
private fun DrawScope.drawAnnotationItem(stroke: AnnotationStroke) {
    if (stroke.points.isEmpty()) return

    when (stroke.tool) {
        AnnotationTool.PEN -> {
            if (stroke.points.size < 2) {
                drawCircle(stroke.color, radius = stroke.strokeWidth / 2f, center = stroke.points.first())
            } else {
                val path = Path().apply {
                    moveTo(stroke.points.first().x, stroke.points.first().y)
                    for (i in 1 until stroke.points.size) {
                        val p0 = stroke.points[i - 1]
                        val p1 = stroke.points[i]
                        quadraticTo(p0.x, p0.y, (p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                    }
                    lineTo(stroke.points.last().x, stroke.points.last().y)
                }
                drawPath(
                    path = path,
                    color = stroke.color,
                    style = Stroke(
                        width = stroke.strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        AnnotationTool.ARROW -> {
            val start = stroke.points.first()
            val end = stroke.points.last()
            drawLine(stroke.color, start, end, strokeWidth = stroke.strokeWidth, cap = StrokeCap.Round)

            val dx = end.x - start.x
            val dy = end.y - start.y
            val angle = atan2(dy, dx)
            val arrowHeadLen = (stroke.strokeWidth * 3.5f).coerceIn(20f, 44f)
            val arrowAngle = Math.PI / 6.0

            val x1 = end.x - arrowHeadLen * cos(angle - arrowAngle).toFloat()
            val y1 = end.y - arrowHeadLen * sin(angle - arrowAngle).toFloat()
            val x2 = end.x - arrowHeadLen * cos(angle + arrowAngle).toFloat()
            val y2 = end.y - arrowHeadLen * sin(angle + arrowAngle).toFloat()

            val arrowPath = Path().apply {
                moveTo(end.x, end.y)
                lineTo(x1, y1)
                lineTo(x2, y2)
                close()
            }
            drawPath(arrowPath, stroke.color)
        }

        AnnotationTool.RECTANGLE -> {
            val start = stroke.points.first()
            val end = stroke.points.last()
            val left = min(start.x, end.x)
            val top = min(start.y, end.y)
            val right = max(start.x, end.x)
            val bottom = max(start.y, end.y)
            drawRect(
                color = stroke.color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = stroke.strokeWidth)
            )
        }

        AnnotationTool.CIRCLE -> {
            val start = stroke.points.first()
            val end = stroke.points.last()
            val left = min(start.x, end.x)
            val top = min(start.y, end.y)
            val right = max(start.x, end.x)
            val bottom = max(start.y, end.y)
            drawOval(
                color = stroke.color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = stroke.strokeWidth)
            )
        }
    }
}

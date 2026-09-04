package com.screenpro.ui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

@Composable
fun FaceCamBubble(
    shapeType: String = "circle",
    sizeType: String = "medium",
    borderWidthDp: Int = 3,
    borderColorHex: String = "#FF4B2B",
    isMirrored: Boolean = true,
    isRecordingActive: Boolean = false,
    initialPosX: Float = 0.75f,
    initialPosY: Float = 0.08f,
    onPositionChanged: (Float, Float) -> Unit = { _, _ -> },
    onSizeChanged: (String, Float) -> Unit = { _, _ -> },
    onShapeChanged: (String) -> Unit = {},
    onMirrorToggled: (Boolean) -> Unit = {},
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // Bubble dimension based on sizeType
    val (bubbleWidthDp, bubbleHeightDp, bubbleScale) = when (sizeType) {
        "small" -> when (shapeType) {
            "rectangle" -> Triple(130.dp, 98.dp, 0.20f)
            else -> Triple(110.dp, 110.dp, 0.20f)
        }
        "large" -> when (shapeType) {
            "rectangle" -> Triple(200.dp, 150.dp, 0.35f)
            else -> Triple(175.dp, 175.dp, 0.35f)
        }
        else -> when (shapeType) { // medium
            "rectangle" -> Triple(165.dp, 124.dp, 0.26f)
            else -> Triple(140.dp, 140.dp, 0.26f)
        }
    }

    val bubbleWidthPx = with(density) { bubbleWidthDp.toPx() }
    val bubbleHeightPx = with(density) { bubbleHeightDp.toPx() }

    // Initial offset in pixels calculated from normalized percentage
    var offsetX by remember {
        mutableFloatStateOf(initialPosX * (screenWidthPx - bubbleWidthPx).coerceAtLeast(1f))
    }
    var offsetY by remember {
        mutableFloatStateOf(initialPosY * (screenHeightPx - bubbleHeightPx).coerceAtLeast(1f))
    }

    var showControls by remember { mutableStateOf(false) }

    val parsedBorderColor = try {
        Color(android.graphics.Color.parseColor(borderColorHex))
    } catch (_: Exception) {
        Color(0xFFFF4B2B)
    }

    val bubbleShape: Shape = when (shapeType) {
        "rounded-square" -> RoundedCornerShape(26.dp)
        "rectangle" -> RoundedCornerShape(16.dp)
        else -> CircleShape
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(width = bubbleWidthDp, height = bubbleHeightDp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val newX = (offsetX + dragAmount.x).coerceIn(0f, (screenWidthPx - bubbleWidthPx).coerceAtLeast(0f))
                    val newY = (offsetY + dragAmount.y).coerceIn(0f, (screenHeightPx - bubbleHeightPx).coerceAtLeast(0f))
                    offsetX = newX
                    offsetY = newY

                    val pctX = (newX / (screenWidthPx - bubbleWidthPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val pctY = (newY / (screenHeightPx - bubbleHeightPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onPositionChanged(pctX, pctY)
                }
            }
            .clip(bubbleShape)
            .background(Color.Black)
            .border(borderWidthDp.dp, parsedBorderColor, bubbleShape)
            .clickable { showControls = !showControls }
    ) {
        if (!isRecordingActive) {
            // Live Preview using CameraX when in positioning/setup mode
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        if (isMirrored) scaleX = -1f else scaleX = 1f
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                update = { view ->
                    view.scaleX = if (isMirrored) -1f else 1f
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // During active recording, the video compositor has hardware camera lock
            // Show sleek active recording status in the on-screen preview
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF161616)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        tint = parsedBorderColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "REC CAM",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Active in Video",
                        fontSize = 9.sp,
                        color = Color.LightGray
                    )
                }
            }
        }

        // Quick Controls Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(6.dp)
            ) {
                // Close button top-right
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(26.dp)
                        .align(Alignment.TopEnd)
                        .background(Color.Red.copy(alpha = 0.8f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(14.dp))
                }

                // Quick toggles column / grid
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Shape switcher
                        IconButton(
                            onClick = {
                                val nextShape = when (shapeType) {
                                    "circle" -> "rounded-square"
                                    "rounded-square" -> "rectangle"
                                    else -> "circle"
                                }
                                onShapeChanged(nextShape)
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2E2E2E), CircleShape)
                        ) {
                            Icon(
                                when (shapeType) {
                                    "rounded-square" -> Icons.Default.Square
                                    "rectangle" -> Icons.Default.AspectRatio
                                    else -> Icons.Default.Circle
                                },
                                contentDescription = "Cycle Shape",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Size switcher
                        IconButton(
                            onClick = {
                                val (nextSize, nextScale) = when (sizeType) {
                                    "small" -> "medium" to 0.26f
                                    "medium" -> "large" to 0.35f
                                    else -> "small" to 0.20f
                                }
                                onSizeChanged(nextSize, nextScale)
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2E2E2E), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.PhotoSizeSelectLarge,
                                contentDescription = "Cycle Size",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Mirror toggle
                        IconButton(
                            onClick = { onMirrorToggled(!isMirrored) },
                            modifier = Modifier
                                .size(28.dp)
                                .background(if (isMirrored) parsedBorderColor else Color(0xFF2E2E2E), CircleShape)
                        ) {
                            Icon(Icons.Default.Flip, contentDescription = "Mirror", tint = Color.White, modifier = Modifier.size(16.dp))
                        }

                        // Snap position to next corner
                        IconButton(
                            onClick = {
                                val maxW = (screenWidthPx - bubbleWidthPx).coerceAtLeast(1f)
                                val maxH = (screenHeightPx - bubbleHeightPx).coerceAtLeast(1f)

                                // Check current quadrant and advance clockwise
                                val isRight = offsetX > maxW * 0.5f
                                val isBottom = offsetY > maxH * 0.5f

                                val (targetPctX, targetPctY) = when {
                                    !isRight && !isBottom -> 0.85f to 0.08f // Top-Left -> Top-Right
                                    isRight && !isBottom -> 0.85f to 0.85f // Top-Right -> Bottom-Right
                                    isRight && isBottom -> 0.08f to 0.85f // Bottom-Right -> Bottom-Left
                                    else -> 0.08f to 0.08f // Bottom-Left -> Top-Left
                                }

                                offsetX = targetPctX * maxW
                                offsetY = targetPctY * maxH
                                onPositionChanged(targetPctX, targetPctY)
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2E2E2E), CircleShape)
                        ) {
                            Icon(Icons.Default.FitScreen, contentDescription = "Snap Corner", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

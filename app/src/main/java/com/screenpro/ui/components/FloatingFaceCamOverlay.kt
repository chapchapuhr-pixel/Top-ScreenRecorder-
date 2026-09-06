package com.screenpro.ui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * FloatingFaceCamOverlay
 * Renders the live system-wide floating FaceCam overlay.
 * Features:
 * - Tap facecam to reveal the prominent '×' cancel button outside on top
 * - Shift to hind (back) camera or front camera
 * - Switch shape dynamically (Circle, Rounded Square, Rectangle)
 * - Move anywhere with smooth drag
 * - Clicking '×' cancels / closes the overlay immediately
 */
@Composable
fun FloatingFaceCamOverlay(
    isCollapsed: Boolean,
    shapeType: String = "circle",
    sizeType: String = "medium",
    borderWidthDp: Int = 3,
    borderColorHex: String = "#FF4B2B",
    isMirrored: Boolean = true,
    isFrontCamera: Boolean = true,
    onDrag: (Float, Float) -> Unit,
    onToggleCollapse: (Boolean) -> Unit,
    onSwitchLens: (Boolean) -> Unit,
    onShapeChanged: (String) -> Unit,
    onSizeChanged: (String, Float) -> Unit,
    onMirrorToggled: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    val parsedBorderColor = try {
        Color(android.graphics.Color.parseColor(borderColorHex))
    } catch (_: Exception) {
        Color(0xFFFF4B2B)
    }

    // When collapsed, display a sleek floating mini pill that can be tapped to expand
    if (isCollapsed) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xEE161616))
                .border(1.5.dp, parsedBorderColor, RoundedCornerShape(18.dp))
                .clickable { onToggleCollapse(false) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Show FaceCam",
                    tint = parsedBorderColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Show Cam",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        return
    }

    var showControls by remember { mutableStateOf(false) }

    val bubbleShape: Shape = when (shapeType) {
        "rounded-square" -> RoundedCornerShape(26.dp)
        "rectangle" -> RoundedCornerShape(16.dp)
        else -> CircleShape
    }

    // Root container with drag gesture handling
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        // FaceCam Bubble (Anchored at the bottom of the window canvas)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(top = 34.dp, start = 4.dp, end = 4.dp, bottom = 2.dp)
                .align(Alignment.BottomCenter)
                .clip(bubbleShape)
                .background(Color.Black)
                .border(borderWidthDp.dp, parsedBorderColor, bubbleShape)
                .clickable { showControls = !showControls }
        ) {
            // Continuous Live CameraX Preview (supporting Front and Hind / Back camera)
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        scaleX = if (isMirrored && isFrontCamera) -1f else 1f
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            // Shift between Front and Hind (Back) Camera
                            val cameraSelector = if (isFrontCamera) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }

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
                    view.scaleX = if (isMirrored && isFrontCamera) -1f else 1f
                },
                modifier = Modifier.fillMaxSize()
            )

            // Quick In-Bubble Action HUD (shown when user touches the round facecam)
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Top Row: Shift to Hind/Front camera & Change Shape
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Shift to Hind / Rear Camera Lens
                            IconButton(
                                onClick = { onSwitchLens(!isFrontCamera) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        if (!isFrontCamera) parsedBorderColor else Color(0xFF2E2E2E),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cameraswitch,
                                    contentDescription = if (isFrontCamera) "Shift to Hind Camera" else "Shift to Front Camera",
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            // Change Shape Button (Circle -> Rounded-Square -> Rectangle)
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
                                    .size(32.dp)
                                    .background(Color(0xFF2E2E2E), CircleShape)
                            ) {
                                Icon(
                                    imageVector = when (shapeType) {
                                        "rounded-square" -> Icons.Default.Square
                                        "rectangle" -> Icons.Default.AspectRatio
                                        else -> Icons.Default.Circle
                                    },
                                    contentDescription = "Change Shape",
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        // Bottom Row: Size & Mirroring
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Size Switcher
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
                                    .size(30.dp)
                                    .background(Color(0xFF2E2E2E), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoSizeSelectLarge,
                                    contentDescription = "Cycle Size",
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            // Mirror toggle
                            IconButton(
                                onClick = { onMirrorToggled(!isMirrored) },
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(if (isMirrored) parsedBorderColor else Color(0xFF2E2E2E), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Flip,
                                    contentDescription = "Mirror Preview",
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        // Lens label
                        Text(
                            text = if (isFrontCamera) "Front Cam" else "Hind Cam",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Prominent '×' Cancel Button outside on top of the round facecam
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 6.dp, top = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF2424))
                    .border(2.dp, Color.White, CircleShape)
                    .clickable {
                        onClose()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel and Close FaceCam",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

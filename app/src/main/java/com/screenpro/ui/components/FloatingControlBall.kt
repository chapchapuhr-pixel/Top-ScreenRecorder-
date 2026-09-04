package com.screenpro.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * X Recorder-style floating control ball.
 * Floats unobtrusively on screen, draggable, snaps to edges, dims when idle.
 * Tapping opens an expanded quick-action menu with Record/Pause/Stop,
 * Screenshot, Facecam toggle, Brush/Annotate, and Settings shortcuts.
 */
@Composable
fun FloatingControlBall(
    isRecording: Boolean,
    isPaused: Boolean,
    durationSeconds: Long,
    isFaceCamActive: Boolean,
    isDrawingActive: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onToggleFaceCam: () -> Unit,
    onToggleDrawing: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissBall: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val ballSizeDp = 56.dp
    val ballSizePx = with(density) { ballSizeDp.toPx() }

    // Position state
    var offsetX by remember { mutableStateOf(screenWidthPx - ballSizePx - 24f) }
    var offsetY by remember { mutableStateOf(screenHeightPx * 0.35f) }
    var isDragging by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var isIdle by remember { mutableStateOf(false) }

    // Bottom dismiss area detection
    val dismissThresholdY = screenHeightPx - with(density) { 110.dp.toPx() }
    val isOverDismissArea = isDragging && offsetY > dismissThresholdY

    // Idle timer to dim ball when untouched
    LaunchedEffect(isDragging, isExpanded, isRecording) {
        if (!isDragging && !isExpanded) {
            isIdle = false
            delay(3500)
            isIdle = true
        } else {
            isIdle = false
        }
    }

    // Pulsing transition for active recording indicator
    val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    fun formatDuration(seconds: Long): String {
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Drag-to-Dismiss Drop Target at bottom of screen
        AnimatedVisibility(
            visible = isDragging,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (isOverDismissArea) 72.dp else 60.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOverDismissArea) Color(0xFFFF1744) else Color(0xCC212121)
                    )
                    .border(
                        2.dp,
                        if (isOverDismissArea) Color.White else Color(0xFF616161),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Hide Floating Icon",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    if (isOverDismissArea) {
                        Text(
                            text = "Release",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Tap-outside backdrop when menu is expanded
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isExpanded = false
                    }
            )
        }

        // Expanded Radial / Popup Menu
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(180)) + scaleIn(tween(220, easing = EaseOutBack)),
            exit = fadeOut(tween(150)) + scaleOut(tween(180)),
            modifier = Modifier
                .offset {
                    // Position menu centered near the ball, clamped to stay inside screen bounds
                    val menuWidthPx = with(density) { 290.dp.toPx() }
                    val menuHeightPx = with(density) { 260.dp.toPx() }

                    val targetX = (offsetX + ballSizePx / 2f - menuWidthPx / 2f)
                        .coerceIn(16f, screenWidthPx - menuWidthPx - 16f)
                    val targetY = (offsetY + ballSizePx / 2f - menuHeightPx / 2f)
                        .coerceIn(48f, screenHeightPx - menuHeightPx - 48f)

                    IntOffset(targetX.roundToInt(), targetY.roundToInt())
                }
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF181818),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C2C2C)),
                shadowElevation = 16.dp,
                modifier = Modifier.width(290.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header with recording status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isRecording) {
                                            if (isPaused) Color(0xFFFFB300) else Color(0xFFFF1744)
                                        } else Color(0xFF00E676)
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRecording) {
                                    if (isPaused) "Paused (${formatDuration(durationSeconds)})"
                                    else "Recording (${formatDuration(durationSeconds)})"
                                } else "Ready to Record",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = { isExpanded = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close Menu",
                                tint = Color.LightGray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Primary Recording Actions Row
                    if (isRecording) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Pause / Resume Button
                            Button(
                                onClick = {
                                    if (isPaused) onResumeRecording() else onPauseRecording()
                                    isExpanded = false
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPaused) Color(0xFF2E7D32) else Color(0xFF37474F)
                                )
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isPaused) "Resume" else "Pause",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Stop & Finish Button
                            Button(
                                onClick = {
                                    onStopRecording()
                                    isExpanded = false
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Stop", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        // Start Record Button
                        Button(
                            onClick = {
                                onStartRecording()
                                isExpanded = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF4B2B)
                            )
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Start Recording",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = Color(0xFF2E2E2E))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Secondary Tools Grid: Screenshot, Facecam, Brush, Home
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        FloatingToolItem(
                            icon = Icons.Default.CameraAlt,
                            label = "Capture",
                            isActive = false,
                            activeColor = Color(0xFF00E5FF),
                            onClick = {
                                onTakeScreenshot()
                                isExpanded = false
                            }
                        )

                        FloatingToolItem(
                            icon = Icons.Default.AccountCircle,
                            label = "Facecam",
                            isActive = isFaceCamActive,
                            activeColor = Color(0xFFFF4B2B),
                            onClick = {
                                onToggleFaceCam()
                                isExpanded = false
                            }
                        )

                        FloatingToolItem(
                            icon = Icons.Default.Brush,
                            label = "Draw",
                            isActive = isDrawingActive,
                            activeColor = Color(0xFFFFD600),
                            onClick = {
                                onToggleDrawing()
                                isExpanded = false
                            }
                        )

                        FloatingToolItem(
                            icon = Icons.Default.Home,
                            label = "Home",
                            isActive = false,
                            activeColor = Color.White,
                            onClick = {
                                onOpenHome()
                                isExpanded = false
                            }
                        )

                        FloatingToolItem(
                            icon = Icons.Default.Settings,
                            label = "Settings",
                            isActive = false,
                            activeColor = Color.White,
                            onClick = {
                                onOpenSettings()
                                isExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Floating Ball (The Draggable Bubble itself)
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(ballSizeDp)
                .alpha(if (isExpanded) 1f else if (isIdle) 0.55f else 0.95f)
                .scale(if (isRecording && !isPaused) pulseScale else 1f)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (isRecording) {
                            if (isPaused) listOf(Color(0xFFFFB300), Color(0xFFE65100))
                            else listOf(Color(0xFFFF5252), Color(0xFFD50000))
                        } else {
                            listOf(Color(0xFF2A2A2A), Color(0xFF141414))
                        }
                    )
                )
                .border(
                    2.dp,
                    if (isRecording) {
                        if (isPaused) Color(0xFFFFD54F) else Color(0xFFFF8A80)
                    } else Color(0xFFFF4B2B),
                    CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            isExpanded = !isExpanded
                            isIdle = false
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            isIdle = false
                        },
                        onDragEnd = {
                            isDragging = false
                            if (isOverDismissArea) {
                                onDismissBall()
                            } else {
                                // Snap gently toward nearest left or right edge like XRecorder
                                val margin = 16f
                                val targetSnapX = if (offsetX + ballSizePx / 2f < screenWidthPx / 2f) {
                                    margin
                                } else {
                                    screenWidthPx - ballSizePx - margin
                                }
                                offsetX = targetSnapX
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            isIdle = false
                            offsetX = (offsetX + dragAmount.x).coerceIn(8f, screenWidthPx - ballSizePx - 8f)
                            offsetY = (offsetY + dragAmount.y).coerceIn(40f, screenHeightPx - ballSizePx - 40f)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (isRecording) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.Pause else Icons.Default.FiberManualRecord,
                        contentDescription = "Recording Active",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = formatDuration(durationSeconds),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            } else {
                // Idle icon with video camera glyph
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Screen Recorder Quick Menu",
                    tint = Color(0xFFFF4B2B),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun FloatingToolItem(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (isActive) activeColor.copy(alpha = 0.22f) else Color(0xFF242424))
                .border(
                    1.dp,
                    if (isActive) activeColor else Color(0xFF3A3A3A),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isActive) activeColor else Color.LightGray,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

package com.screenpro.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.screenpro.data.model.AppSettings
import com.screenpro.data.model.MediaItem
import com.screenpro.data.model.MediaType
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isRecording: Boolean,
    isPaused: Boolean,
    elapsedSeconds: Long,
    settings: AppSettings,
    recentItems: List<MediaItem>,
    onStartRecordingClick: () -> Unit,
    onStopRecordingClick: () -> Unit,
    onPauseRecordingClick: () -> Unit,
    onResumeRecordingClick: () -> Unit,
    onTakeScreenshotClick: () -> Unit,
    onToggleDrawingClick: () -> Unit,
    onToggleFaceCamClick: () -> Unit,
    onToggleFloatingBallClick: () -> Unit = {},
    onNavigateLibrary: () -> Unit,
    onNavigateSettings: () -> Unit,
    onPlayItem: (MediaItem) -> Unit,
    onEditItem: (MediaItem) -> Unit,
    onShareItem: (MediaItem) -> Unit,
    onDeleteItem: (MediaItem) -> Unit
) {
    val context = LocalContext.current

    // Pulsing animation when recording
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    fun formatDuration(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.1f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF4B2B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "ScreenPro",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                            Text(
                                text = if (isRecording) {
                                    if (isPaused) "PAUSED" else "LIVE RECORDING"
                                } else "READY TO CAPTURE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isRecording) {
                                    if (isPaused) Color(0xFFFFB300) else Color(0xFFFF5252)
                                } else Color(0xFF00E676),
                                letterSpacing = 1.sp
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0E0E0E)
                )
            )
        },
        containerColor = Color(0xFF0E0E0E)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Hero Recording Trigger Card
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF141414),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF262626)),
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Live Timer or Status Header
                        if (isRecording) {
                            Text(
                                text = formatDuration(elapsedSeconds),
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isPaused) Color(0xFFFFB300) else Color(0xFFFF4B2B),
                                letterSpacing = 2.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isPaused) "Recording is Paused" else "Capturing System Display",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        } else {
                            Text(
                                text = "High Performance Screen Recorder",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Hardware accelerated • Zero watermarks • Low latency",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Large Circular Record Action Button
                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            if (isRecording && !isPaused) {
                                Box(
                                    modifier = Modifier
                                        .size(116.dp)
                                        .scale(pulseScale)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF4B2B).copy(alpha = 0.25f))
                                )
                            }

                            Button(
                                onClick = {
                                    if (isRecording) onStopRecordingClick() else onStartRecordingClick()
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRecording) Color(0xFFFF3333) else Color(0xFFFF4B2B)
                                ),
                                modifier = Modifier.size(96.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(
                                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                    contentDescription = if (isRecording) "Stop" else "Record",
                                    tint = Color.White,
                                    modifier = Modifier.size(46.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Pause / Resume buttons when recording
                        if (isRecording) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = if (isPaused) onResumeRecordingClick else onPauseRecordingClick,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color.White
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3E3E3E))
                                ) {
                                    Icon(
                                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isPaused) "Resume" else "Pause")
                                }

                                Button(
                                    onClick = onStopRecordingClick,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B2B)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Finish")
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Capabilities Badges Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                                .padding(vertical = 10.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AspectRatio, contentDescription = null, tint = Color(0xFFFF4B2B), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    when (settings.videoSizePreset) {
                                        "fullscreen" -> "Fullscreen"
                                        "youtube" -> "16:9 YT"
                                        "social" -> "9:16 Social"
                                        "square" -> "1:1 Square"
                                        "cinema" -> "21:9 Cinema"
                                        "tablet" -> "4:3 Tablet"
                                        else -> "Fullscreen"
                                    },
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text("•", color = Color.DarkGray)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Hd, contentDescription = null, tint = Color(0xFFFF4B2B), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(settings.resolution.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("•", color = Color.DarkGray)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFFFF4B2B), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${settings.fps} FPS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("•", color = Color.DarkGray)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFFF4B2B), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    when (settings.audioSource) {
                                        "mic" -> "Mic"
                                        "internal" -> "Internal"
                                        "both" -> "Dual"
                                        else -> "Mute"
                                    },
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Quick Actions Grid
            item {
                Text(
                    text = "Quick Tools",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionCard(
                        icon = Icons.Default.CameraAlt,
                        label = "Screenshot",
                        sublabel = "Instant capture",
                        onClick = onTakeScreenshotClick,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionCard(
                        icon = Icons.Default.Brush,
                        label = "Annotate",
                        sublabel = "Draw on screen",
                        onClick = onToggleDrawingClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionCard(
                        icon = Icons.Default.AccountCircle,
                        label = "Face Cam",
                        sublabel = if (settings.cameraEnabled) "Cam Active" else "Camera Bubble",
                        onClick = onToggleFaceCamClick,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionCard(
                        icon = Icons.Default.RadioButtonChecked,
                        label = "Floating Ball",
                        sublabel = if (settings.floatingBallEnabled) "Ball Active" else "Ball Hidden",
                        onClick = onToggleFloatingBallClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionCard(
                        icon = Icons.Default.VideoLibrary,
                        label = "Library",
                        sublabel = "${recentItems.size} files",
                        onClick = onNavigateLibrary,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionCard(
                        icon = Icons.Default.Settings,
                        label = "Settings",
                        sublabel = "Resolution & Tools",
                        onClick = onNavigateSettings,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Recent Recordings Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Clips",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    TextButton(onClick = onNavigateLibrary) {
                        Text("View all (${recentItems.size})", color = Color(0xFFFF4B2B), fontSize = 13.sp)
                    }
                }
            }

            if (recentItems.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF141414),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262626))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No recordings yet", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Tap the red record button above to make your first video!", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(recentItems.take(4), key = { it.id }) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayItem(item) },
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF141414),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262626))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Thumbnail Preview
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF222222)),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(item.uri)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (item.type == MediaType.VIDEO) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.6f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(3.dp)
                                            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
                                            .padding(horizontal = 3.dp, vertical = 1.dp)
                                    ) {
                                        Text(formatDuration(item.duration), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Details
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${formatBytes(item.fileSize)} • ${formatDate(item.createdAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }

                            // Actions
                            Row {
                                if (item.type == MediaType.VIDEO) {
                                    IconButton(
                                        onClick = { onEditItem(item) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCut, contentDescription = "Edit", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                    }
                                }

                                IconButton(
                                    onClick = { onShareItem(item) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                }

                                IconButton(
                                    onClick = { onDeleteItem(item) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sublabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF141414),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262626))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF222222)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color(0xFFFF4B2B), modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(text = label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                Text(text = sublabel, color = Color.Gray, fontSize = 10.sp)
            }
        }
    }
}

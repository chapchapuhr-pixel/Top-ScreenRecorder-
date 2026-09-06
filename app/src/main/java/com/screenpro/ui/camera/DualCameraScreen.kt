package com.screenpro.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.screenpro.data.model.MediaItem
import com.screenpro.recording.camera.DualCameraRecordingManager
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualCameraScreen(
    onNavigateBack: () -> Unit,
    onOpenPlayer: (MediaItem) -> Unit = {},
    onOpenEditor: (MediaItem) -> Unit = {}
) {
    val context = LocalContext.current
    val manager = remember { DualCameraRecordingManager(context) }

    DisposableEffect(Unit) {
        onDispose {
            manager.release()
        }
    }

    // Permission checks
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasCameraPermission = perms[Manifest.permission.CAMERA] == true
        hasMicPermission = perms[Manifest.permission.RECORD_AUDIO] == true
        if (hasCameraPermission) {
            manager.refreshSupport()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission || !hasMicPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    // States from manager
    val supportInfo by manager.supportInfo.collectAsState()
    val isRecording by manager.isRecording.collectAsState()
    val elapsedSec by manager.elapsedSeconds.collectAsState()
    val isFrontMain by manager.isFrontMain.collectAsState()
    val layout by manager.layout.collectAsState()
    val pipShape by manager.pipShape.collectAsState()
    val pipCorner by manager.pipCorner.collectAsState()
    val pipScale by manager.pipScale.collectAsState()
    val isMirrored by manager.isMirrored.collectAsState()
    val isLandscape by manager.isLandscape.collectAsState()
    val enableMic by manager.enableMic.collectAsState()

    var showSavedDialog by remember { mutableStateOf<MediaItem?>(null) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Dual Camera Studio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isFrontMain) "Front Main + Rear PiP" else "Rear Main + Front PiP",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isRecording) {
                                manager.stopRecording { onNavigateBack() }
                            } else {
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.testTag("dual_camera_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Quick Swap Cameras Button
                    IconButton(
                        onClick = { manager.swapCameras() },
                        enabled = !isRecording,
                        modifier = Modifier.testTag("dual_camera_swap_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Swap Front and Rear Cameras",
                            tint = if (!isRecording) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }

                    // Layout Mode Selector Button
                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.testTag("dual_camera_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Camera Controls & Layouts"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = Color(0xFF10131A)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasCameraPermission) {
                // Permission required view
                CameraPermissionCard(
                    onRequest = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                    }
                )
            } else {
                // Main Camera Viewport
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    // Live TextureView Preview
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        val surface = Surface(st)
                                        manager.attachPreviewSurface(surface)
                                    }

                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("dual_camera_preview_viewport")
                    )

                    // Hardware Support Warning Banner if concurrent capture is unsupported
                    if (!supportInfo.isSupported) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .align(Alignment.TopCenter)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Warning",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Dual Camera isn't supported on this device.",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Simultaneous front + rear camera recording requires hardware OEM concurrent camera support (Android 11+ / API 30+). Single camera FaceCam remains fully operational.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }

                    // On-Screen PiP Draggable Touch Guide / Feedback (when in PiP mode)
                    if (layout == "pip") {
                        DraggablePipOverlay(
                            manager = manager,
                            pipShape = pipShape,
                            pipScale = pipScale,
                            isFrontMain = isFrontMain
                        )
                    }

                    // Top Status Overlay (Timer and Mode)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Badge indicating hardware readiness
                        Surface(
                            color = if (supportInfo.isSupported) Color(0xCC00C853) else Color(0xCCFF5252),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color.White, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (supportInfo.isSupported) "DUAL READY" else "UNSUPPORTED",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        // Live Recording Timer Badge
                        if (isRecording) {
                            Surface(
                                color = Color(0xDDFF1744),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BlinkingDot()
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = formatTime(elapsedSec),
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Floating Studio Controls
                    DualCameraBottomControls(
                        isRecording = isRecording,
                        isSupported = supportInfo.isSupported,
                        layout = layout,
                        pipShape = pipShape,
                        enableMic = enableMic,
                        isMirrored = isMirrored,
                        isLandscape = isLandscape,
                        onRecordToggle = {
                            if (isRecording) {
                                manager.stopRecording { item ->
                                    if (item != null) {
                                        showSavedDialog = item
                                    } else {
                                        Toast.makeText(context, "Recording completed & saved", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                manager.startRecording { error ->
                                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onSwap = { manager.swapCameras() },
                        onSelectLayout = { manager.setLayout(it) },
                        onToggleMic = { manager.toggleMic() },
                        onToggleMirror = { manager.toggleMirror() },
                        onToggleOrientation = { manager.toggleOrientation() },
                        onOpenSettings = { showSettingsSheet = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    )
                }
            }
        }

        // Settings / Customization Bottom Sheet
        if (showSettingsSheet) {
            DualCameraSettingsModalSheet(
                manager = manager,
                layout = layout,
                pipShape = pipShape,
                pipCorner = pipCorner,
                pipScale = pipScale,
                isMirrored = isMirrored,
                isFrontMain = isFrontMain,
                onDismiss = { showSettingsSheet = false }
            )
        }

        // Saved Video Dialog
        showSavedDialog?.let { mediaItem ->
            DualCameraSavedDialog(
                mediaItem = mediaItem,
                onDismiss = { showSavedDialog = null },
                onPlay = {
                    showSavedDialog = null
                    onOpenPlayer(mediaItem)
                },
                onEdit = {
                    showSavedDialog = null
                    onOpenEditor(mediaItem)
                }
            )
        }
    }
}

@Composable
private fun DraggablePipOverlay(
    manager: DualCameraRecordingManager,
    pipShape: String,
    pipScale: Float,
    isFrontMain: Boolean
) {
    val pipPosX by manager.pipPosX.collectAsState()
    val pipPosY by manager.pipPosY.collectAsState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxW = maxWidth.value
        val maxH = maxHeight.value

        val bubbleWidth = (maxW * pipScale).coerceIn(80f, 220f)
        val bubbleHeight = if (pipShape == "rectangle") bubbleWidth * 1.33f else bubbleWidth

        val offsetX = (pipPosX * (maxW - bubbleWidth)).coerceIn(0f, maxW - bubbleWidth)
        val offsetY = (pipPosY * (maxH - bubbleHeight)).coerceIn(0f, maxH - bubbleHeight)

        val shape = when (pipShape) {
            "circle" -> CircleShape
            "rounded-square" -> RoundedCornerShape(20.dp)
            else -> RoundedCornerShape(8.dp)
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(bubbleWidth.dp, bubbleHeight.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val newX = (offsetX + dragAmount.x) / (maxW - bubbleWidth)
                        val newY = (offsetY + dragAmount.y) / (maxH - bubbleHeight)
                        manager.setPipPosition(newX, newY)
                    }
                }
                .border(2.5.dp, Color(0xFFFF3D00), shape)
                .clip(shape)
                .background(Color(0x33FF3D00)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.OpenWith,
                    contentDescription = "Drag to reposition PiP",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = if (isFrontMain) "Rear" else "Front",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DualCameraBottomControls(
    isRecording: Boolean,
    isSupported: Boolean,
    layout: String,
    pipShape: String,
    enableMic: Boolean,
    isMirrored: Boolean,
    isLandscape: Boolean,
    onRecordToggle: () -> Unit,
    onSwap: () -> Unit,
    onSelectLayout: (String) -> Unit,
    onToggleMic: () -> Unit,
    onToggleMirror: () -> Unit,
    onToggleOrientation: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xEE1E2430),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 8.dp,
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Quick layout mode selector chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LayoutModeChip(
                    title = "PiP Window",
                    icon = Icons.Default.PictureInPictureAlt,
                    selected = layout == "pip",
                    onClick = { onSelectLayout("pip") }
                )
                LayoutModeChip(
                    title = "Split Side",
                    icon = Icons.Default.ViewColumn,
                    selected = layout == "split_vertical",
                    onClick = { onSelectLayout("split_vertical") }
                )
                LayoutModeChip(
                    title = "Split Top",
                    icon = Icons.Default.TableRows,
                    selected = layout == "split_horizontal",
                    onClick = { onSelectLayout("split_horizontal") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action row: Mic, Swap, RECORD, Mirror, Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic toggle
                IconButton(
                    onClick = onToggleMic,
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            if (enableMic) Color(0x3300E5FF) else Color(0x33888888),
                            CircleShape
                        )
                        .testTag("dual_camera_mic_toggle")
                ) {
                    Icon(
                        imageVector = if (enableMic) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Toggle Microphone",
                        tint = if (enableMic) Color(0xFF00E5FF) else Color.LightGray
                    )
                }

                // Swap cameras
                IconButton(
                    onClick = onSwap,
                    enabled = !isRecording,
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color(0x22FFFFFF), CircleShape)
                        .testTag("dual_camera_swap_lens_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FlipCameraAndroid,
                        contentDescription = "Swap Front and Rear Lens",
                        tint = if (!isRecording) Color.White else Color.Gray
                    )
                }

                // Central Record / Stop Button
                RecordControlButton(
                    isRecording = isRecording,
                    isSupported = isSupported,
                    onClick = onRecordToggle
                )

                // Mirror toggle
                IconButton(
                    onClick = onToggleMirror,
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            if (isMirrored) Color(0x33FF9100) else Color(0x22FFFFFF),
                            CircleShape
                        )
                        .testTag("dual_camera_mirror_toggle")
                ) {
                    Icon(
                        imageVector = Icons.Default.CompareArrows,
                        contentDescription = "Mirror Front Camera",
                        tint = if (isMirrored) Color(0xFFFF9100) else Color.White
                    )
                }

                // More controls / sheet
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color(0x22FFFFFF), CircleShape)
                        .testTag("dual_camera_more_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "More Options",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutModeChip(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color(0x22FFFFFF),
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (selected) Color.White else Color.LightGray,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Color.White else Color.LightGray
            )
        }
    }
}

@Composable
private fun RecordControlButton(
    isRecording: Boolean,
    isSupported: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .testTag("dual_camera_record_toggle_button")
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0x44FF1744))
            )
        }

        Surface(
            onClick = {
                if (isSupported) {
                    onClick()
                }
            },
            shape = CircleShape,
            color = if (!isSupported) Color(0xFF555555) else if (isRecording) Color(0xFFFF1744) else Color(0xFFFF3D00),
            shadowElevation = 6.dp,
            modifier = Modifier.size(62.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DualCameraSettingsModalSheet(
    manager: DualCameraRecordingManager,
    layout: String,
    pipShape: String,
    pipCorner: String,
    pipScale: Float,
    isMirrored: Boolean,
    isFrontMain: Boolean,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Dual Camera Customization",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            // PiP Shape
            Text(
                text = "Secondary Window Shape",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = pipShape == "circle",
                    onClick = { manager.setPipShape("circle") },
                    label = { Text("Circular") },
                    leadingIcon = { Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null) }
                )
                FilterChip(
                    selected = pipShape == "rounded-square",
                    onClick = { manager.setPipShape("rounded-square") },
                    label = { Text("Rounded Rect") },
                    leadingIcon = { Icon(Icons.Default.CropPortrait, contentDescription = null) }
                )
                FilterChip(
                    selected = pipShape == "rectangle",
                    onClick = { manager.setPipShape("rectangle") },
                    label = { Text("Rectangle") },
                    leadingIcon = { Icon(Icons.Default.CropLandscape, contentDescription = null) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Corner Snap
            Text(
                text = "Snap to Corner",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { manager.setPipCorner("top_left") },
                    modifier = Modifier.weight(1f)
                ) { Text("Top-L", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { manager.setPipCorner("top_right") },
                    modifier = Modifier.weight(1f)
                ) { Text("Top-R", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { manager.setPipCorner("bottom_left") },
                    modifier = Modifier.weight(1f)
                ) { Text("Btm-L", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { manager.setPipCorner("bottom_right") },
                    modifier = Modifier.weight(1f)
                ) { Text("Btm-R", fontSize = 12.sp) }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Secondary Window Size Slider
            Text(
                text = "Secondary Window Size: ${(pipScale * 100).toInt()}%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = pipScale,
                onValueChange = { manager.setPipScale(it) },
                valueRange = 0.18f..0.42f,
                steps = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Toggles: Mirror front, swap lens
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Mirror Front Camera",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Flip front camera preview horizontally",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isMirrored,
                    onCheckedChange = { manager.toggleMirror() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DualCameraSavedDialog(
    mediaItem: MediaItem,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onEdit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Saved",
                tint = Color(0xFF00C853),
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Dual Camera Video Saved!",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Text(
                text = "Your dual camera recording (${mediaItem.title}) has been safely saved to your App Library and Phone Gallery.",
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play Video")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun CameraPermissionCard(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Camera Permission",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Camera & Audio Access Needed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Dual Camera recording requires camera and microphone permissions to capture front and rear video feeds simultaneously.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}

@Composable
private fun BlinkingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blinkAlpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .background(Color.White.copy(alpha = alpha), CircleShape)
    )
}

private fun formatTime(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

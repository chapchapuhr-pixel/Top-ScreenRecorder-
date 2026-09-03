package com.screenpro.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

data class DrawingPath(
    val path: Path,
    val color: Color,
    val strokeWidth: Float
)

@Composable
fun DrawingOverlay(
    onClose: () -> Unit
) {
    val paths = remember { mutableStateListOf<DrawingPath>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var selectedColor by remember { mutableStateOf(Color(0xFFFF4B2B)) }
    var strokeWidth by remember { mutableFloatStateOf(8f) }
    var isEraser by remember { mutableStateOf(false) }

    val colors = listOf(
        Color(0xFFFF4B2B), // Red
        Color(0xFFFF9800), // Orange
        Color(0xFFFFEB3B), // Yellow
        Color(0xFF4CAF50), // Green
        Color(0xFF00E5FF), // Cyan
        Color(0xFF9C27B0), // Purple
        Color.White
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
    ) {
        // Drawing Surface
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(selectedColor, strokeWidth, isEraser) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val newPath = Path().apply { moveTo(offset.x, offset.y) }
                            currentPath = newPath
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentPath?.lineTo(change.position.x, change.position.y)
                        },
                        onDragEnd = {
                            currentPath?.let {
                                paths.add(
                                    DrawingPath(
                                        path = it,
                                        color = if (isEraser) Color.Transparent else selectedColor,
                                        strokeWidth = strokeWidth
                                    )
                                )
                            }
                            currentPath = null
                        }
                    )
                }
        ) {
            for (p in paths) {
                if (p.color != Color.Transparent) {
                    drawPath(
                        path = p.path,
                        color = p.color,
                        style = Stroke(
                            width = p.strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
            currentPath?.let {
                if (!isEraser) {
                    drawPath(
                        path = it,
                        color = selectedColor,
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }

        // Top Controls Panel
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF141414).copy(alpha = 0.95f),
            tonalElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E2E))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Screen Annotations",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = {
                                if (paths.isNotEmpty()) paths.removeAt(paths.size - 1)
                            }
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo", tint = Color.LightGray)
                        }

                        IconButton(
                            onClick = { paths.clear() }
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = Color(0xFFFF5252))
                        }

                        IconButton(
                            onClick = onClose
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                // Color Palette & Tools
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable {
                                    selectedColor = color
                                    isEraser = false
                                }
                                .then(
                                    if (selectedColor == color && !isEraser) {
                                        Modifier.border(2.dp, Color.White, CircleShape)
                                    } else Modifier
                                )
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    FilterChip(
                        selected = isEraser,
                        onClick = { isEraser = !isEraser },
                        label = { Text("Eraser") },
                        leadingIcon = { Icon(Icons.Default.AutoFixNormal, null) }
                    )
                }
            }
        }
    }
}

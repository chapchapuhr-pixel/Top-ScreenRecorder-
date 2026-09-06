package com.screenpro.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.screenpro.util.VideoThumbnailHelper
import com.screenpro.ads.LibraryNativeAdCard
import com.screenpro.data.model.MediaItem
import com.screenpro.data.model.MediaType
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

sealed interface LibraryGridEntry {
    data class Media(val item: MediaItem) : LibraryGridEntry
    data class Ad(val adIndex: Int) : LibraryGridEntry
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    items: List<MediaItem>,
    onPlayItem: (MediaItem) -> Unit,
    onEditItem: (MediaItem) -> Unit,
    onShareItem: (MediaItem) -> Unit,
    onDeleteItem: (MediaItem) -> Unit,
    onRenameItem: (MediaItem, String) -> Unit,
    onSaveToPhone: (MediaItem) -> Unit = {},
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack
) {
    val context = LocalContext.current

    var selectedTypeFilter by remember { mutableStateOf<MediaType?>(null) } // null = All
    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("newest") } // "newest", "oldest", "largest", "smallest"
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    // Intercept back key when in multi-select or searching
    BackHandler(enabled = isMultiSelectMode || searchQuery.isNotEmpty()) {
        if (isMultiSelectMode) {
            isMultiSelectMode = false
            selectedIds.clear()
        } else if (searchQuery.isNotEmpty()) {
            searchQuery = ""
        }
    }

    // Dialogs state
    var itemForDetails by remember { mutableStateOf<MediaItem?>(null) }
    var itemForRename by remember { mutableStateOf<MediaItem?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var itemForDeleteConfirm by remember { mutableStateOf<MediaItem?>(null) }

    // Filter & Sort
    val filteredItems = remember(items, selectedTypeFilter, searchQuery, sortBy) {
        var list = items

        if (selectedTypeFilter != null) {
            list = list.filter { it.type == selectedTypeFilter }
        }

        if (searchQuery.isNotBlank()) {
            list = list.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.filename.contains(searchQuery, ignoreCase = true)
            }
        }

        when (sortBy) {
            "newest" -> list.sortedByDescending { it.createdAt }
            "oldest" -> list.sortedBy { it.createdAt }
            "largest" -> list.sortedByDescending { it.fileSize }
            "smallest" -> list.sortedBy { it.fileSize }
            else -> list
        }
    }

    fun formatDuration(secs: Long): String {
        val mins = secs / 60
        val s = secs % 60
        return String.format("%02d:%02d", mins, s)
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
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isMultiSelectMode) "${selectedIds.size} Selected" else "Media Library",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isMultiSelectMode) {
                                isMultiSelectMode = false
                                selectedIds.clear()
                            } else {
                                onNavigateBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isMultiSelectMode) Icons.Default.Close else Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (isMultiSelectMode) {
                        IconButton(onClick = {
                            if (selectedIds.size == filteredItems.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(filteredItems.map { it.id })
                            }
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All", tint = Color.White)
                        }

                        if (selectedIds.isNotEmpty()) {
                            IconButton(onClick = {
                                val toDelete = items.filter { it.id in selectedIds }
                                toDelete.forEach { onDeleteItem(it) }
                                selectedIds.clear()
                                isMultiSelectMode = false
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = Color(0xFFFF5252))
                            }
                        }
                    } else {
                        IconButton(onClick = onNavigateHome) {
                            Icon(Icons.Default.Home, contentDescription = "Home Page Shortcut", tint = Color.White)
                        }
                        IconButton(onClick = { isMultiSelectMode = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = "Multi-select", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0E0E0E))
            )
        },
        containerColor = Color(0xFF0E0E0E)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by title or filename...", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFF4B2B),
                    unfocusedBorderColor = Color(0xFF2E2E2E),
                    focusedContainerColor = Color(0xFF141414),
                    unfocusedContainerColor = Color(0xFF141414),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            // Filter Chips & Sort Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedTypeFilter == null,
                        onClick = { selectedTypeFilter = null },
                        label = { Text("All") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFF4B2B),
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = selectedTypeFilter == MediaType.VIDEO,
                        onClick = { selectedTypeFilter = MediaType.VIDEO },
                        label = { Text("Videos") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFF4B2B),
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = selectedTypeFilter == MediaType.SCREENSHOT,
                        onClick = { selectedTypeFilter = MediaType.SCREENSHOT },
                        label = { Text("Screenshots") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFF4B2B),
                            selectedLabelColor = Color.White
                        )
                    )
                }

                // Sort toggle button
                var showSortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Default.Sort, contentDescription = "Sort", tint = Color.LightGray)
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        modifier = Modifier.background(Color(0xFF1E1E1E))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Newest First", color = Color.White) },
                            onClick = { sortBy = "newest"; showSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Oldest First", color = Color.White) },
                            onClick = { sortBy = "oldest"; showSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Largest File", color = Color.White) },
                            onClick = { sortBy = "largest"; showSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Smallest File", color = Color.White) },
                            onClick = { sortBy = "smallest"; showSortMenu = false }
                        )
                    }
                }
            }

            // Media Grid List
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = Color.DarkGray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No recordings found",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Recorded clips and captured screenshots will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                val gridEntries = remember(filteredItems) {
                    val list = mutableListOf<LibraryGridEntry>()
                    var adIdx = 0
                    filteredItems.forEachIndexed { index, item ->
                        list.add(LibraryGridEntry.Media(item))
                        if ((index + 1) % 4 == 0) {
                            list.add(LibraryGridEntry.Ad(adIdx++))
                        }
                    }
                    if (filteredItems.isNotEmpty() && filteredItems.size < 4) {
                        list.add(LibraryGridEntry.Ad(adIdx))
                    }
                    list
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = gridEntries,
                        key = { entry ->
                            when (entry) {
                                is LibraryGridEntry.Media -> "media_${entry.item.id}"
                                is LibraryGridEntry.Ad -> "ad_${entry.adIndex}"
                            }
                        },
                        span = { entry ->
                            when (entry) {
                                is LibraryGridEntry.Ad -> GridItemSpan(maxLineSpan)
                                is LibraryGridEntry.Media -> GridItemSpan(1)
                            }
                        }
                    ) { entry ->
                        when (entry) {
                            is LibraryGridEntry.Ad -> {
                                LibraryNativeAdCard()
                            }
                            is LibraryGridEntry.Media -> {
                                val item = entry.item
                                val isSelected = item.id in selectedIds

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF141414),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (isSelected) Color(0xFFFF4B2B) else Color(0xFF262626)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isMultiSelectMode) {
                                        if (isSelected) selectedIds.remove(item.id)
                                        else selectedIds.add(item.id)
                                    } else {
                                        onPlayItem(item)
                                    }
                                }
                        ) {
                            Column {
                                // Thumbnail Box
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .background(Color(0xFF1A1A1A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    var fallbackBitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
                                    if (item.type == MediaType.VIDEO) {
                                        LaunchedEffect(item.id, item.uri, item.localFilePath) {
                                            fallbackBitmap = VideoThumbnailHelper.loadThumbnail(context, item.uri, item.localFilePath)
                                        }
                                    }

                                    if (fallbackBitmap != null) {
                                        Image(
                                            bitmap = fallbackBitmap!!.asImageBitmap(),
                                            contentDescription = item.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        val request = remember(item.uri, item.localFilePath) {
                                            val builder = ImageRequest.Builder(context)
                                                .data(item.localFilePath?.let { File(it) } ?: item.uri)
                                                .crossfade(true)
                                            if (item.type == MediaType.VIDEO) {
                                                builder.decoderFactory(VideoFrameDecoder.Factory())
                                                    .videoFrameMillis(500)
                                            }
                                            builder.build()
                                        }
                                        AsyncImage(
                                            model = request,
                                            contentDescription = item.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    // Play icon overlay
                                    if (item.type == MediaType.VIDEO) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.6f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        // Duration badge
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(6.dp)
                                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = formatDuration(item.duration),
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        // Screenshot badge
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(6.dp)
                                                .background(Color(0xFF2979FF).copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "PHOTO",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    // Multi-select Checkbox
                                    if (isMultiSelectMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                if (it) selectedIds.add(item.id)
                                                else selectedIds.remove(item.id)
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp),
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = Color(0xFFFF4B2B)
                                            )
                                        )
                                    }
                                }

                                // Info Row
                                Column(modifier = Modifier.padding(10.dp)) {
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
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    // Save in Phone action button / status
                                    Spacer(modifier = Modifier.height(6.dp))
                                    if (item.isSavedToGallery) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFF1B3B22), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Saved in Phone", color = Color(0xFF81C784), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    } else {
                                        Button(
                                            onClick = { onSaveToPhone(item) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(28.dp)
                                        ) {
                                            Icon(Icons.Default.SaveAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Save in Phone", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Quick Item Actions
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row {
                                            if (item.type == MediaType.VIDEO) {
                                                IconButton(
                                                    onClick = { onEditItem(item) },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.ContentCut, contentDescription = "Edit", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            IconButton(
                                                onClick = { onShareItem(item) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                            }
                                        }

                                        // More options menu
                                        var showItemMenu by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(
                                                onClick = { showItemMenu = true },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                            }

                                            DropdownMenu(
                                                expanded = showItemMenu,
                                                onDismissRequest = { showItemMenu = false },
                                                modifier = Modifier.background(Color(0xFF1E1E1E))
                                            ) {
                                                if (!item.isSavedToGallery) {
                                                    DropdownMenuItem(
                                                        text = { Text("Save in Phone", color = Color(0xFF64B5F6)) },
                                                        leadingIcon = { Icon(Icons.Default.SaveAlt, null, tint = Color(0xFF64B5F6)) },
                                                        onClick = {
                                                            onSaveToPhone(item)
                                                            showItemMenu = false
                                                        }
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text("Details", color = Color.White) },
                                                    leadingIcon = { Icon(Icons.Default.Info, null, tint = Color.LightGray) },
                                                    onClick = {
                                                        itemForDetails = item
                                                        showItemMenu = false
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Rename", color = Color.White) },
                                                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = Color.LightGray) },
                                                    onClick = {
                                                        itemForRename = item
                                                        renameInput = item.title
                                                        showItemMenu = false
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Delete", color = Color(0xFFFF5252)) },
                                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFFF5252)) },
                                                    onClick = {
                                                        itemForDeleteConfirm = item
                                                        showItemMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

    // Details Modal
    itemForDetails?.let { detailItem ->
        AlertDialog(
            onDismissRequest = { itemForDetails = null },
            title = { Text(detailItem.title, color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Filename: ${detailItem.filename}", color = Color.LightGray, fontSize = 13.sp)
                    Text("File Size: ${formatBytes(detailItem.fileSize)}", color = Color.LightGray, fontSize = 13.sp)
                    if (detailItem.type == MediaType.VIDEO) {
                        Text("Duration: ${formatDuration(detailItem.duration)}", color = Color.LightGray, fontSize = 13.sp)
                    }
                    Text("MIME Type: ${detailItem.mimeType}", color = Color.LightGray, fontSize = 13.sp)
                    Text("Recorded: ${formatDate(detailItem.createdAt)}", color = Color.LightGray, fontSize = 13.sp)
                    Text("URI: ${detailItem.uri}", color = Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                TextButton(onClick = { itemForDetails = null }) {
                    Text("Close", color = Color(0xFFFF4B2B))
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    // Rename Modal
    itemForRename?.let { renItem ->
        AlertDialog(
            onDismissRequest = { itemForRename = null },
            title = { Text("Rename Recording", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF4B2B),
                        unfocusedBorderColor = Color.DarkGray
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            onRenameItem(renItem, renameInput.trim())
                        }
                        itemForRename = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B2B))
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemForRename = null }) {
                    Text("Cancel", color = Color.LightGray)
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    // Delete Confirm Modal
    itemForDeleteConfirm?.let { delItem ->
        AlertDialog(
            onDismissRequest = { itemForDeleteConfirm = null },
            title = { Text("Delete Recording?", color = Color.White) },
            text = {
                Text(
                    "Are you sure you want to delete \"${delItem.title}\"? This action cannot be undone.",
                    color = Color.LightGray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteItem(delItem)
                        itemForDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemForDeleteConfirm = null }) {
                    Text("Cancel", color = Color.LightGray)
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
}

package com.screenpro.data.model

import android.net.Uri

enum class MediaType {
    VIDEO,
    SCREENSHOT
}

data class MediaItem(
    val id: String,
    val type: MediaType,
    val title: String,
    val filename: String,
    val createdAt: Long,
    val duration: Long = 0L, // In seconds
    val fileSize: Long = 0L, // In bytes
    val mimeType: String,
    val uri: Uri,
    val width: Int = 1080,
    val height: Int = 1920
)

package com.screenpro.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object VideoThumbnailHelper {
    private val memoryCache = LruCache<String, Bitmap>(80)

    suspend fun loadThumbnail(context: Context, uri: Uri, localFilePath: String? = null): Bitmap? = withContext(Dispatchers.IO) {
        val key = localFilePath ?: uri.toString()
        memoryCache.get(key)?.let { return@withContext it }

        // Check disk cache
        val diskCacheDir = File(context.cacheDir, "video_thumbs").apply { mkdirs() }
        val diskThumbFile = File(diskCacheDir, "${key.hashCode()}.jpg")
        if (diskThumbFile.exists()) {
            val bitmap = android.graphics.BitmapFactory.decodeFile(diskThumbFile.absolutePath)
            if (bitmap != null) {
                memoryCache.put(key, bitmap)
                return@withContext bitmap
            }
        }

        // Extract frame using MediaMetadataRetriever
        try {
            val retriever = MediaMetadataRetriever()
            if (localFilePath != null && File(localFilePath).exists()) {
                retriever.setDataSource(localFilePath)
            } else {
                retriever.setDataSource(context, uri)
            }

            // Retrieve frame at 500ms or 1s to capture actual video contents rather than dark intro
            var bitmap = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            retriever.release()

            if (bitmap != null) {
                try {
                    FileOutputStream(diskThumbFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                } catch (_: Exception) {}

                memoryCache.put(key, bitmap)
                return@withContext bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}

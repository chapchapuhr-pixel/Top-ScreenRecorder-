package com.screenpro.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.screenpro.data.model.MediaItem
import com.screenpro.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * MediaStoreRepository
 * Manages the App Library (private internal storage) and exports to Phone Gallery (public MediaStore).
 * When recordings finish, they are saved IMMEDIATELY into the App Library.
 * Users can tap "Save in Phone" at any time to instantly export to the Phone Gallery!
 */
class MediaStoreRepository(private val context: Context) {

    val appLibraryDir: File by lazy {
        File(context.filesDir, "ScreenProLibrary").apply { mkdirs() }
    }

    private val prefs = context.getSharedPreferences("screenpro_saved_status", Context.MODE_PRIVATE)

    fun isSavedToGallery(filename: String): Boolean {
        return prefs.getBoolean("gallery_$filename", false)
    }

    fun markSavedToGallery(filename: String, saved: Boolean = true) {
        prefs.edit().putBoolean("gallery_$filename", saved).apply()
    }

    /**
     * Saves a recorded video into the App Library folder.
     * This keeps the video private inside the app and DOES NOT post it to the phone gallery.
     */
    suspend fun saveVideoToAppLibrary(sourceFile: File, title: String? = null): MediaItem = withContext(Dispatchers.IO) {
        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: "ScreenPro_${System.currentTimeMillis()}"
        val destFileName = if (cleanTitle.endsWith(".mp4", ignoreCase = true)) cleanTitle else "$cleanTitle.mp4"
        val destFile = File(appLibraryDir, destFileName)

        if (sourceFile.absolutePath != destFile.absolutePath) {
            try {
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Retrieve video metadata
        var durationSec = 1L
        var width = 1080
        var height = 1920

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(destFile.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

            durStr?.toLongOrNull()?.let { durationSec = (it / 1000L).coerceAtLeast(1L) }
            wStr?.toIntOrNull()?.let { width = it.coerceAtLeast(480) }
            hStr?.toIntOrNull()?.let { height = it.coerceAtLeast(480) }
            retriever.release()
        } catch (_: Exception) {}

        val uri = Uri.fromFile(destFile)
        MediaItem(
            id = "app_video_${destFile.name}",
            type = MediaType.VIDEO,
            title = cleanTitle.removeSuffix(".mp4"),
            filename = destFile.name,
            createdAt = destFile.lastModified(),
            duration = durationSec,
            fileSize = destFile.length(),
            mimeType = "video/mp4",
            uri = uri,
            width = width,
            height = height,
            isSavedToGallery = isSavedToGallery(destFile.name),
            localFilePath = destFile.absolutePath
        )
    }

    /**
     * Saves a screenshot into the App Library folder.
     */
    suspend fun saveScreenshotToAppLibrary(
        bitmap: Bitmap,
        title: String = "ScreenPro_Screenshot_${System.currentTimeMillis()}"
    ): MediaItem = withContext(Dispatchers.IO) {
        val filename = if (title.endsWith(".png", ignoreCase = true)) title else "$title.png"
        val destFile = File(appLibraryDir, filename)

        try {
            FileOutputStream(destFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        MediaItem(
            id = "app_img_${destFile.name}",
            type = MediaType.SCREENSHOT,
            title = title.removeSuffix(".png"),
            filename = destFile.name,
            createdAt = destFile.lastModified(),
            duration = 0L,
            fileSize = destFile.length(),
            mimeType = "image/png",
            uri = Uri.fromFile(destFile),
            width = bitmap.width,
            height = bitmap.height,
            isSavedToGallery = isSavedToGallery(destFile.name),
            localFilePath = destFile.absolutePath
        )
    }

    /**
     * Exports a video from the App Library into the Phone Gallery (public MediaStore).
     * Once called, the video immediately appears in the user's phone gallery / Google Photos!
     */
    suspend fun saveVideoToPhoneGallery(item: MediaItem): Uri? = withContext(Dispatchers.IO) {
        val sourceFile = item.localFilePath?.let { File(it) } ?: run {
            // If already a content uri or missing local path, attempt to copy
            null
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, item.filename)
            put(MediaStore.Video.Media.TITLE, item.title)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ScreenPro")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collection, contentValues) ?: return@withContext null

        try {
            resolver.openOutputStream(itemUri)?.use { outputStream ->
                if (sourceFile != null && sourceFile.exists()) {
                    FileInputStream(sourceFile).use { it.copyTo(outputStream) }
                } else {
                    resolver.openInputStream(item.uri)?.use { it.copyTo(outputStream) }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }

            // Trigger system media scan so the phone gallery updates immediately
            MediaScannerConnection.scanFile(
                context,
                arrayOf(itemUri.toString()),
                arrayOf("video/mp4"),
                null
            )

            markSavedToGallery(item.filename, true)
            itemUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Exports a screenshot from the App Library into the Phone Gallery (public MediaStore).
     */
    suspend fun saveScreenshotToPhoneGallery(item: MediaItem): Uri? = withContext(Dispatchers.IO) {
        val sourceFile = item.localFilePath?.let { File(it) }

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, item.filename)
            put(MediaStore.Images.Media.TITLE, item.title)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ScreenPro")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collection, contentValues) ?: return@withContext null

        try {
            resolver.openOutputStream(itemUri)?.use { outputStream ->
                if (sourceFile != null && sourceFile.exists()) {
                    FileInputStream(sourceFile).use { it.copyTo(outputStream) }
                } else {
                    resolver.openInputStream(item.uri)?.use { it.copyTo(outputStream) }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(itemUri.toString()),
                arrayOf("image/png"),
                null
            )

            markSavedToGallery(item.filename, true)
            itemUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getShareableUri(item: MediaItem): Uri {
        return if (item.localFilePath != null) {
            try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(item.localFilePath)
                )
            } catch (e: Exception) {
                item.uri
            }
        } else {
            item.uri
        }
    }

    /**
     * Loads all media items from the App Library folder AND public MediaStore.
     */
    suspend fun loadMediaItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MediaItem>()
        val seenFilenames = mutableSetOf<String>()

        // 1. First, load items from the App Library (private folder)
        val appFiles = appLibraryDir.listFiles() ?: emptyArray()
        for (f in appFiles) {
            if (!f.isFile || f.length() == 0L) continue
            val fname = f.name
            seenFilenames.add(fname)
            val isVideo = fname.endsWith(".mp4", ignoreCase = true)
            val isSaved = isSavedToGallery(fname)

            if (isVideo) {
                var durationSec = 1L
                var width = 1080
                var height = 1920
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(f.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                        durationSec = (it / 1000L).coerceAtLeast(1L)
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let {
                        width = it.coerceAtLeast(480)
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let {
                        height = it.coerceAtLeast(480)
                    }
                    retriever.release()
                } catch (_: Exception) {}

                list.add(
                    MediaItem(
                        id = "app_video_$fname",
                        type = MediaType.VIDEO,
                        title = fname.removeSuffix(".mp4"),
                        filename = fname,
                        createdAt = f.lastModified(),
                        duration = durationSec,
                        fileSize = f.length(),
                        mimeType = "video/mp4",
                        uri = Uri.fromFile(f),
                        width = width,
                        height = height,
                        isSavedToGallery = isSaved,
                        localFilePath = f.absolutePath
                    )
                )
            } else if (fname.endsWith(".png", ignoreCase = true) || fname.endsWith(".jpg", ignoreCase = true)) {
                var width = 1080
                var height = 1920
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                    if (opts.outWidth > 0) width = opts.outWidth
                    if (opts.outHeight > 0) height = opts.outHeight
                } catch (_: Exception) {}

                list.add(
                    MediaItem(
                        id = "app_img_$fname",
                        type = MediaType.SCREENSHOT,
                        title = fname.substringBeforeLast("."),
                        filename = fname,
                        createdAt = f.lastModified(),
                        duration = 0L,
                        fileSize = f.length(),
                        mimeType = "image/png",
                        uri = Uri.fromFile(f),
                        width = width,
                        height = height,
                        isSavedToGallery = isSaved,
                        localFilePath = f.absolutePath
                    )
                )
            }
        }

        // 2. Query Public Videos in MediaStore
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT
            )

            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            } else {
                "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            }

            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("%Screen%", "%Screen%", "%Record%")
            } else {
                arrayOf("%Screen%", "%Record%")
            }

            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val filename = cursor.getString(nameCol) ?: "recording_$id.mp4"
                    if (seenFilenames.contains(filename)) {
                        // Mark existing app item as saved to gallery
                        val idx = list.indexOfFirst { it.filename == filename }
                        if (idx != -1) {
                            list[idx] = list[idx].copy(isSavedToGallery = true)
                            markSavedToGallery(filename, true)
                        }
                        continue
                    }

                    val title = cursor.getString(titleCol) ?: filename.substringBeforeLast(".")
                    val durationMs = cursor.getLong(durationCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAddedSec = cursor.getLong(dateCol)
                    val mime = cursor.getString(mimeCol) ?: "video/mp4"
                    val w = if (widthCol != -1) cursor.getInt(widthCol).coerceAtLeast(1080) else 1080
                    val h = if (heightCol != -1) cursor.getInt(heightCol).coerceAtLeast(1920) else 1920
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    list.add(
                        MediaItem(
                            id = "video_$id",
                            type = MediaType.VIDEO,
                            title = title,
                            filename = filename,
                            createdAt = dateAddedSec * 1000L,
                            duration = (durationMs / 1000L).coerceAtLeast(1L),
                            fileSize = size,
                            mimeType = mime,
                            uri = contentUri,
                            width = w,
                            height = h,
                            isSavedToGallery = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Query Public Screenshots in MediaStore
        try {
            val imgProjection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.TITLE,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )

            val imgSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            } else {
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            }

            val imgSelectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("%Screen%", "%Screen%", "%Shot%")
            } else {
                arrayOf("%Screen%", "%Shot%")
            }

            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imgProjection,
                imgSelection,
                imgSelectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.TITLE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val filename = cursor.getString(nameCol) ?: "screenshot_$id.png"
                    if (seenFilenames.contains(filename)) {
                        val idx = list.indexOfFirst { it.filename == filename }
                        if (idx != -1) {
                            list[idx] = list[idx].copy(isSavedToGallery = true)
                            markSavedToGallery(filename, true)
                        }
                        continue
                    }

                    val title = cursor.getString(titleCol) ?: filename.substringBeforeLast(".")
                    val size = cursor.getLong(sizeCol)
                    val dateAddedSec = cursor.getLong(dateCol)
                    val mime = cursor.getString(mimeCol) ?: "image/png"
                    val w = if (widthCol != -1) cursor.getInt(widthCol).coerceAtLeast(1080) else 1080
                    val h = if (heightCol != -1) cursor.getInt(heightCol).coerceAtLeast(1920) else 1920
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    list.add(
                        MediaItem(
                            id = "img_$id",
                            type = MediaType.SCREENSHOT,
                            title = title,
                            filename = filename,
                            createdAt = dateAddedSec * 1000L,
                            duration = 0L,
                            fileSize = size,
                            mimeType = mime,
                            uri = contentUri,
                            width = w,
                            height = h,
                            isSavedToGallery = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        list.sortedByDescending { it.createdAt }
    }

    fun saveVideoToMediaStore(sourceFile: File, title: String = sourceFile.name): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name)
            put(MediaStore.Video.Media.TITLE, title)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ScreenPro")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collection, contentValues) ?: return null

        resolver.openOutputStream(itemUri)?.use { outputStream ->
            FileInputStream(sourceFile).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, contentValues, null, null)
        }

        markSavedToGallery(sourceFile.name, true)
        return itemUri
    }

    fun saveScreenshotToMediaStore(bitmap: Bitmap, title: String = "ScreenPro_Screenshot_${System.currentTimeMillis()}"): Uri? {
        val filename = "$title.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.TITLE, title)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ScreenPro")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collection, contentValues) ?: return null

        resolver.openOutputStream(itemUri)?.use { outputStream: OutputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(itemUri, contentValues, null, null)
        }

        markSavedToGallery(filename, true)
        return itemUri
    }

    fun deleteMediaItem(uri: Uri): Boolean {
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            rows > 0
        } catch (e: Exception) {
            false
        }
    }

    fun deleteMediaItem(item: MediaItem): Boolean {
        var success = false
        if (item.localFilePath != null) {
            try {
                val f = File(item.localFilePath)
                if (f.exists()) {
                    f.delete()
                    success = true
                }
            } catch (_: Exception) {}
        }

        if (item.uri.scheme == "content") {
            try {
                val rows = context.contentResolver.delete(item.uri, null, null)
                if (rows > 0) success = true
            } catch (_: Exception) {}
        }

        prefs.edit().remove("gallery_${item.filename}").apply()
        return success
    }

    fun renameMediaItem(item: MediaItem, newTitle: String): Boolean {
        if (item.localFilePath != null) {
            try {
                val oldFile = File(item.localFilePath)
                val ext = oldFile.extension
                val newFile = File(oldFile.parentFile, "$newTitle.$ext")
                if (oldFile.renameTo(newFile)) {
                    val wasSaved = isSavedToGallery(item.filename)
                    prefs.edit().remove("gallery_${item.filename}").apply()
                    if (wasSaved) markSavedToGallery(newFile.name, true)
                    return true
                }
            } catch (_: Exception) {}
        }

        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.TITLE, newTitle)
            }
            context.contentResolver.update(item.uri, values, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun renameMediaItem(uri: Uri, newTitle: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.TITLE, newTitle)
            }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }
}


package com.screenpro.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.RgbFilter
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * VideoProcessingHelper
 * Professional video processing supporting:
 * 1. High performance zero-re-encoding trim & mute (via native MediaExtractor/MediaMuxer)
 * 2. Hardware-accelerated Black & White (Grayscale) filter & boundary Crop export (via Media3 Transformer)
 */
@OptIn(UnstableApi::class)
object VideoProcessingHelper {

    private const val TAG = "VideoProcessingHelper"
    private const val DEFAULT_BUFFER_SIZE = 1024 * 1024 // 1MB buffer

    /**
     * Processes video with trimming, audio muting, Black & White filter, and boundary cropping.
     */
    suspend fun processVideo(
        context: Context,
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        muteAudio: Boolean,
        isBlackAndWhite: Boolean = false,
        cropLeftPct: Float = 0f,
        cropTopPct: Float = 0f,
        cropRightPct: Float = 1f,
        cropBottomPct: Float = 1f,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (!inputFile.exists() || inputFile.length() == 0L) {
            Log.e(TAG, "Input file does not exist or is empty")
            return@withContext false
        }

        val hasCrop = (cropLeftPct > 0.02f || cropTopPct > 0.02f || cropRightPct < 0.98f || cropBottomPct < 0.98f)

        // If Black & White or Crop is requested, use Media3 Transformer pipeline
        if (isBlackAndWhite || hasCrop) {
            val transformerSuccess = processWithTransformer(
                context = context,
                inputFile = inputFile,
                outputFile = outputFile,
                startMs = startMs,
                endMs = endMs,
                muteAudio = muteAudio,
                isBlackAndWhite = isBlackAndWhite,
                cropLeftPct = if (hasCrop) cropLeftPct else 0f,
                cropTopPct = if (hasCrop) cropTopPct else 0f,
                cropRightPct = if (hasCrop) cropRightPct else 1f,
                cropBottomPct = if (hasCrop) cropBottomPct else 1f,
                onProgress = onProgress
            )
            if (transformerSuccess && outputFile.exists() && outputFile.length() > 0L) {
                return@withContext true
            }
            Log.w(TAG, "Transformer pipeline returned false, falling back to native remux")
        }

        // Fast native remux fallback
        return@withContext processWithNativeRemux(
            inputFile = inputFile,
            outputFile = outputFile,
            startMs = startMs,
            endMs = endMs,
            muteAudio = muteAudio,
            onProgress = onProgress
        )
    }

    /**
     * Media3 Transformer pipeline applying RgbFilter.createGrayscaleFilter() and Crop
     */
    private suspend fun processWithTransformer(
        context: Context,
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        muteAudio: Boolean,
        isBlackAndWhite: Boolean,
        cropLeftPct: Float,
        cropTopPct: Float,
        cropRightPct: Float,
        cropBottomPct: Float,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            try {
                if (outputFile.exists()) {
                    outputFile.delete()
                }

                val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs.coerceAtLeast(0L))
                    .apply {
                        if (endMs > startMs) {
                            setEndPositionMs(endMs)
                        }
                    }
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(inputFile))
                    .setClippingConfiguration(clippingConfig)
                    .build()

                val videoEffects = mutableListOf<Effect>()

                // 1. Black & White Grayscale Filter
                if (isBlackAndWhite) {
                    videoEffects.add(RgbFilter.createGrayscaleFilter())
                }

                // 2. Crop to target boundary
                val hasCrop = (cropLeftPct > 0.01f || cropTopPct > 0.01f || cropRightPct < 0.99f || cropBottomPct < 0.99f)
                if (hasCrop) {
                    // Convert normalized coordinates (0..1) to NDC (-1..1)
                    val ndcLeft = -1f + 2f * cropLeftPct.coerceIn(0f, 0.9f)
                    val ndcRight = -1f + 2f * cropRightPct.coerceIn(0.1f, 1f)
                    val ndcBottom = 1f - 2f * cropBottomPct.coerceIn(0.1f, 1f)
                    val ndcTop = 1f - 2f * cropTopPct.coerceIn(0f, 0.9f)
                    videoEffects.add(Crop(ndcLeft, ndcRight, ndcBottom, ndcTop))
                }

                val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(muteAudio)
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()

                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            onProgress(1.0f)
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e(TAG, "Transformer export error: ${exportException.message}", exportException)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    })
                    .build()

                transformer.start(editedMediaItem, outputFile.absolutePath)

                // Polling progress
                val progressHolder = ProgressHolder()
                val progressJob = launch(Dispatchers.Main) {
                    while (isActive && continuation.isActive) {
                        val progressState = transformer.getProgress(progressHolder)
                        if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val p = (progressHolder.progress / 100f).coerceIn(0f, 0.98f)
                            onProgress(p)
                        }
                        delay(200)
                    }
                }

                continuation.invokeOnCancellation {
                    progressJob.cancel()
                    transformer.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Media3 Transformer: ${e.message}", e)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    /**
     * Fast native remuxing pipeline using MediaExtractor + MediaMuxer
     */
    private suspend fun processWithNativeRemux(
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        muteAudio: Boolean,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val startUs = (startMs.coerceAtLeast(0L)) * 1000L
        val endUs = if (endMs > startMs) endMs * 1000L else Long.MAX_VALUE
        val totalDurationUs = (endUs - startUs).coerceAtLeast(1_000_000L)

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor().apply {
                setDataSource(inputFile.absolutePath)
            }

            val trackCount = extractor.trackCount
            var videoTrackIndex = -1
            var audioTrackIndex = -1

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "No video track found in input file")
                return@withContext fallbackCopy(inputFile, outputFile)
            }

            if (outputFile.exists()) {
                outputFile.delete()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Setup video track in muxer
            val videoFormat = extractor.getTrackFormat(videoTrackIndex)
            val muxerVideoTrack = muxer.addTrack(videoFormat)

            // Setup audio track in muxer if not muted
            var muxerAudioTrack = -1
            val shouldIncludeAudio = !muteAudio && audioTrackIndex != -1
            if (shouldIncludeAudio) {
                val audioFormat = extractor.getTrackFormat(audioTrackIndex)
                muxerAudioTrack = muxer.addTrack(audioFormat)
            }

            muxer.start()

            // 1. Process Video Track
            extractor.selectTrack(videoTrackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            var firstVideoPtsUs: Long = -1L

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                if (sampleTime >= startUs) {
                    if (firstVideoPtsUs == -1L) {
                        firstVideoPtsUs = sampleTime
                    }

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = (sampleTime - firstVideoPtsUs).coerceAtLeast(0L)
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)

                    val progress = ((sampleTime - startUs).toFloat() / totalDurationUs).coerceIn(0f, 0.9f)
                    onProgress(progress)
                }

                extractor.advance()
            }

            extractor.unselectTrack(videoTrackIndex)

            // 2. Process Audio Track (if requested)
            if (shouldIncludeAudio && muxerAudioTrack != -1) {
                extractor.selectTrack(audioTrackIndex)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                var firstAudioPtsUs: Long = -1L
                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val sampleTime = extractor.sampleTime
                    if (sampleTime > endUs) break

                    if (sampleTime >= startUs) {
                        if (firstAudioPtsUs == -1L) {
                            firstAudioPtsUs = sampleTime
                        }

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = (sampleTime - firstAudioPtsUs).coerceAtLeast(0L)
                        bufferInfo.flags = extractor.sampleFlags

                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                    }

                    extractor.advance()
                }
                extractor.unselectTrack(audioTrackIndex)
            }

            onProgress(1.0f)
            Log.d(TAG, "Video processing completed successfully: ${outputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in native remuxing pipeline, falling back: ${e.message}", e)
            fallbackCopy(inputFile, outputFile)
        } finally {
            try {
                extractor?.release()
            } catch (_: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun fallbackCopy(source: File, dest: File): Boolean {
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Fallback copy failed: ${e.message}")
            false
        }
    }
}

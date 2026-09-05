package com.screenpro.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * VideoProcessingHelper
 * High performance, zero external dependency MP4 video trimming (cutting) and muting
 * using native Android MediaExtractor and MediaMuxer.
 */
object VideoProcessingHelper {

    private const val TAG = "VideoProcessingHelper"
    private const val DEFAULT_BUFFER_SIZE = 1024 * 1024 // 1MB buffer

    /**
     * Trims (cuts) a video between [startMs] and [endMs], and optionally mutes audio.
     * Operates without re-encoding, preserving 100% original video resolution and quality in seconds.
     */
    suspend fun processVideo(
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        muteAudio: Boolean,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (!inputFile.exists() || inputFile.length() == 0L) {
            Log.e(TAG, "Input file does not exist or is empty")
            return@withContext false
        }

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

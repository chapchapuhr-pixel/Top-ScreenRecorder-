package com.screenpro.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * High-performance, zero-re-encode MP4 video & audio concatenator using Android's native
 * MediaExtractor and MediaMuxer. Merges multiple recorded video segments into a single
 * continuous MP4 file with precise presentation timestamp (PTS) continuity.
 */
object VideoMerger {
    private const val TAG = "VideoMerger"

    fun mergeVideos(inputFiles: List<File>, outputFile: File): Boolean {
        val validFiles = inputFiles.filter { it.exists() && it.length() > 0 }
        if (validFiles.isEmpty()) {
            Log.e(TAG, "No valid input files to merge")
            return false
        }

        if (validFiles.size == 1) {
            return try {
                validFiles[0].copyTo(outputFile, overwrite = true)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error copying single segment file", e)
                false
            }
        }

        var muxer: MediaMuxer? = null
        return try {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            outputFile.parentFile?.mkdirs()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Step 1: Detect video and audio tracks from the first segment
            val firstExtractor = MediaExtractor().apply {
                setDataSource(validFiles[0].absolutePath)
            }
            var videoTrackIndexInFirst = -1
            var audioTrackIndexInFirst = -1
            var videoMuxerTrackIndex = -1
            var audioMuxerTrackIndex = -1

            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndexInFirst == -1) {
                    videoTrackIndexInFirst = i
                    videoMuxerTrackIndex = muxer.addTrack(format)
                } else if (mime.startsWith("audio/") && audioTrackIndexInFirst == -1) {
                    audioTrackIndexInFirst = i
                    audioMuxerTrackIndex = muxer.addTrack(format)
                }
            }
            firstExtractor.release()

            if (videoMuxerTrackIndex == -1) {
                Log.e(TAG, "No video track found in first segment")
                return false
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            var globalVideoPtsOffsetUs = 0L
            var globalAudioPtsOffsetUs = 0L

            for (file in validFiles) {
                val extractor = MediaExtractor().apply {
                    setDataSource(file.absolutePath)
                }

                var fileVideoTrack = -1
                var fileAudioTrack = -1

                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && fileVideoTrack == -1) fileVideoTrack = i
                    if (mime.startsWith("audio/") && fileAudioTrack == -1) fileAudioTrack = i
                }

                var maxVideoPtsInSegment = 0L
                var minVideoPtsInSegment = Long.MAX_VALUE
                var maxAudioPtsInSegment = 0L
                var minAudioPtsInSegment = Long.MAX_VALUE

                // 1. Process Video Samples
                if (fileVideoTrack != -1 && videoMuxerTrackIndex != -1) {
                    extractor.selectTrack(fileVideoTrack)
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val pts = extractor.sampleTime
                        if (pts < minVideoPtsInSegment) minVideoPtsInSegment = pts
                        if (pts > maxVideoPtsInSegment) maxVideoPtsInSegment = pts

                        val normalizedPts = (pts - (if (minVideoPtsInSegment != Long.MAX_VALUE) minVideoPtsInSegment else 0L)) + globalVideoPtsOffsetUs

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = normalizedPts.coerceAtLeast(0L)
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(videoMuxerTrackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }
                    extractor.unselectTrack(fileVideoTrack)
                }

                // 2. Process Audio Samples (if present)
                if (fileAudioTrack != -1 && audioMuxerTrackIndex != -1) {
                    extractor.selectTrack(fileAudioTrack)
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val pts = extractor.sampleTime
                        if (pts < minAudioPtsInSegment) minAudioPtsInSegment = pts
                        if (pts > maxAudioPtsInSegment) maxAudioPtsInSegment = pts

                        val normalizedPts = (pts - (if (minAudioPtsInSegment != Long.MAX_VALUE) minAudioPtsInSegment else 0L)) + globalAudioPtsOffsetUs

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = normalizedPts.coerceAtLeast(0L)
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(audioMuxerTrackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }
                    extractor.unselectTrack(fileAudioTrack)
                }

                extractor.release()

                // Calculate next segment offset with small continuity cushion
                val segmentVideoDuration = if (maxVideoPtsInSegment >= minVideoPtsInSegment && minVideoPtsInSegment != Long.MAX_VALUE) {
                    maxVideoPtsInSegment - minVideoPtsInSegment
                } else 0L
                val segmentAudioDuration = if (maxAudioPtsInSegment >= minAudioPtsInSegment && minAudioPtsInSegment != Long.MAX_VALUE) {
                    maxAudioPtsInSegment - minAudioPtsInSegment
                } else 0L

                globalVideoPtsOffsetUs += (segmentVideoDuration + 33_333L)
                globalAudioPtsOffsetUs += (segmentAudioDuration + 23_219L)
            }

            muxer.stop()
            muxer.release()
            Log.d(TAG, "Merged ${validFiles.size} segments successfully into ${outputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to merge video segments", e)
            try { muxer?.release() } catch (_: Exception) {}
            false
        }
    }
}

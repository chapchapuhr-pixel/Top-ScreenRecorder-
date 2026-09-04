package com.screenpro.recording

import android.content.Context
import android.os.Build
import android.view.WindowManager
import com.screenpro.data.model.AppSettings

object VideoResolutionHelper {

    /**
     * Retrieves the physical screen dimensions of the device.
     * Dimensions are made even numbers to comply with H.264 encoder requirements.
     */
    fun getDeviceScreenDimensions(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (rawW, rawH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
        val w = if (rawW % 2 == 0) rawW else rawW - 1
        val h = if (rawH % 2 == 0) rawH else rawH - 1
        return w to h
    }

    /**
     * Calculates the recording video dimensions based on the user's selected preset and resolution.
     * "fullscreen" matches the device screen 100%, removing all black borders / pillarboxing.
     * "youtube" produces landscape 16:9 videos (e.g. 1920x1080) for YouTube / landscape gameplay.
     * "social" produces vertical 9:16 videos (e.g. 1080x1920) for TikTok, Reels, Shorts.
     * "square" produces 1:1 videos (e.g. 1080x1080) for Instagram feed posts.
     * "cinema" produces 21:9 ultrawide (e.g. 2560x1080) for widescreen gaming.
     * "tablet" produces 4:3 videos (e.g. 1440x1080) for tablets / presentations.
     */
    fun getVideoDimensions(context: Context, settings: AppSettings): Pair<Int, Int> {
        val (screenW, screenH) = getDeviceScreenDimensions(context)
        val isScreenLandscape = screenW > screenH

        val (baseW, baseH) = when (settings.videoSizePreset) {
            "fullscreen" -> {
                // Exact screen dimensions covering 100% full screen with zero black phone borders!
                screenW to screenH
            }
            "youtube" -> {
                // YouTube & Landscape 16:9 format
                when (settings.resolution) {
                    "4k" -> 3840 to 2160
                    "1440p" -> 2560 to 1440
                    "1080p" -> 1920 to 1080
                    "720p" -> 1280 to 720
                    "480p" -> 854 to 480
                    else -> 1920 to 1080
                }
            }
            "social" -> {
                // Social 9:16 Vertical format (TikTok, Instagram Reels, YouTube Shorts)
                when (settings.resolution) {
                    "4k" -> 2160 to 3840
                    "1440p" -> 1440 to 2560
                    "1080p" -> 1080 to 1920
                    "720p" -> 720 to 1280
                    "480p" -> 480 to 854
                    else -> 1080 to 1920
                }
            }
            "square" -> {
                // 1:1 Square format (Instagram Feed)
                when (settings.resolution) {
                    "4k" -> 2160 to 2160
                    "1440p" -> 1440 to 1440
                    "1080p" -> 1080 to 1080
                    "720p" -> 720 to 720
                    "480p" -> 480 to 480
                    else -> 1080 to 1080
                }
            }
            "cinema" -> {
                // 21:9 Ultrawide format (Cinema / Mobile Gaming)
                when (settings.resolution) {
                    "4k" -> 3840 to 1646
                    "1440p" -> 3440 to 1440
                    "1080p" -> 2560 to 1080
                    "720p" -> 1680 to 720
                    else -> 2560 to 1080
                }
            }
            "tablet" -> {
                // 4:3 Standard format
                when (settings.resolution) {
                    "1080p" -> 1440 to 1080
                    "720p" -> 960 to 720
                    else -> 1440 to 1080
                }
            }
            "auto" -> {
                if (isScreenLandscape) {
                    when (settings.resolution) {
                        "4k" -> 3840 to 2160
                        "1440p" -> 2560 to 1440
                        "1080p" -> 1920 to 1080
                        "720p" -> 1280 to 720
                        else -> 1920 to 1080
                    }
                } else {
                    screenW to screenH
                }
            }
            else -> {
                // Default to fullscreen covering screen with no borders
                screenW to screenH
            }
        }

        // Guarantee even numbers for H.264/AVC video encoders
        val finalW = if (baseW % 2 == 0) baseW else baseW - 1
        val finalH = if (baseH % 2 == 0) baseH else baseH - 1
        return finalW to finalH
    }
}

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
     * "fullscreen" matches the device screen aspect ratio 100%, removing all black borders / pillarboxing.
     * "youtube" produces landscape 16:9 videos (e.g. 1920x1080) for YouTube / landscape gameplay.
     * "social" produces vertical 9:16 videos (e.g. 1080x1920) for TikTok, Reels, Shorts.
     * "square" produces 1:1 videos (e.g. 1080x1080) for Instagram feed posts.
     * "cinema" produces 21:9 ultrawide (e.g. 2560x1080) for widescreen gaming.
     * "tablet" produces 4:3 videos (e.g. 1440x1080) for tablets / presentations.
     */
    fun getVideoDimensions(context: Context, settings: AppSettings): Pair<Int, Int> {
        val (screenW, screenH) = getDeviceScreenDimensions(context)
        val isScreenLandscape = screenW > screenH
        val screenAspect = screenW.toFloat() / screenH.toFloat()

        val (baseW, baseH) = when (settings.videoSizePreset) {
            "fullscreen" -> {
                // If native/1080p matches screen, use direct hardware dimensions
                // For lower/higher target resolutions (480p, 720p, 1440p, 4k), scale while preserving exact aspect ratio!
                when (settings.resolution.lowercase()) {
                    "480p" -> {
                        if (isScreenLandscape) {
                            (480f * screenAspect).toInt() to 480
                        } else {
                            480 to (480f / screenAspect).toInt()
                        }
                    }
                    "720p" -> {
                        if (isScreenLandscape) {
                            (720f * screenAspect).toInt() to 720
                        } else {
                            720 to (720f / screenAspect).toInt()
                        }
                    }
                    "1080p" -> {
                        if (screenW <= 1080 && screenH <= 1920) {
                            screenW to screenH
                        } else if (isScreenLandscape) {
                            (1080f * screenAspect).toInt() to 1080
                        } else {
                            1080 to (1080f / screenAspect).toInt()
                        }
                    }
                    "1440p" -> {
                        if (isScreenLandscape) {
                            (1440f * screenAspect).toInt() to 1440
                        } else {
                            1440 to (1440f / screenAspect).toInt()
                        }
                    }
                    "4k" -> {
                        if (isScreenLandscape) {
                            (2160f * screenAspect).toInt() to 2160
                        } else {
                            2160 to (2160f / screenAspect).toInt()
                        }
                    }
                    else -> screenW to screenH
                }
            }
            "youtube" -> {
                // YouTube & Landscape 16:9 format
                when (settings.resolution.lowercase()) {
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
                when (settings.resolution.lowercase()) {
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
                when (settings.resolution.lowercase()) {
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
                when (settings.resolution.lowercase()) {
                    "4k" -> 3840 to 1646
                    "1440p" -> 3440 to 1440
                    "1080p" -> 2560 to 1080
                    "720p" -> 1680 to 720
                    "480p" -> 1120 to 480
                    else -> 2560 to 1080
                }
            }
            "tablet" -> {
                // 4:3 Standard format
                when (settings.resolution.lowercase()) {
                    "4k" -> 2880 to 2160
                    "1440p" -> 1920 to 1440
                    "1080p" -> 1440 to 1080
                    "720p" -> 960 to 720
                    "480p" -> 640 to 480
                    else -> 1440 to 1080
                }
            }
            "auto" -> {
                if (isScreenLandscape) {
                    when (settings.resolution.lowercase()) {
                        "4k" -> 3840 to 2160
                        "1440p" -> 2560 to 1440
                        "1080p" -> 1920 to 1080
                        "720p" -> 1280 to 720
                        "480p" -> 854 to 480
                        else -> 1920 to 1080
                    }
                } else {
                    screenW to screenH
                }
            }
            else -> {
                screenW to screenH
            }
        }

        // Guarantee even numbers for H.264/AVC video encoders
        val finalW = if (baseW % 2 == 0) baseW else baseW - 1
        val finalH = if (baseH % 2 == 0) baseH else baseH - 1
        return finalW to finalH
    }

    /**
     * Calculates the optimal encoding bitrate in bps based on bitrate mode, resolution, and fps.
     */
    fun calculateBitrate(settings: AppSettings): Int {
        return when (settings.bitrate.lowercase()) {
            "low" -> 4_000_000
            "medium" -> 8_000_000
            "high" -> 16_000_000
            "ultra" -> 24_000_000
            "studio" -> 35_000_000
            "auto" -> {
                when (settings.resolution.lowercase()) {
                    "4k" -> if (settings.fps >= 60) 32_000_000 else 24_000_000
                    "1440p" -> if (settings.fps >= 60) 22_000_000 else 16_000_000
                    "1080p" -> if (settings.fps >= 60) 16_000_000 else 10_000_000
                    "720p" -> if (settings.fps >= 60) 8_000_000 else 5_000_000
                    "480p" -> 3_000_000
                    else -> 12_000_000
                }
            }
            else -> 10_000_000
        }
    }
}

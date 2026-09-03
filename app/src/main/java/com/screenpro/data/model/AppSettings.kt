package com.screenpro.data.model

data class AppSettings(
    val resolution: String = "1080p",
    val fps: Int = 60,
    val bitrate: String = "high",
    val countdown: Int = 3,
    val showTouches: Boolean = false,
    val audioSource: String = "mic", // "mic", "internal", "both", "disabled"
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    val cameraEnabled: Boolean = false,
    val cameraShape: String = "circle", // "circle", "rounded-square"
    val cameraMirrored: Boolean = true,
    val filenamePrefix: String = "ScreenPro_",
    val preserveSource: Boolean = true,
    val themeMode: String = "dark" // "dark", "light", "system"
)

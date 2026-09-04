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
    val cameraShape: String = "circle", // "circle", "rounded-square", "rectangle"
    val cameraPosition: String = "top-right", // "top-left", "top-right", "bottom-left", "bottom-right", "center", "custom"
    val cameraPositionX: Float = 0.75f, // 0.0 to 1.0
    val cameraPositionY: Float = 0.08f, // 0.0 to 1.0
    val cameraSize: String = "medium", // "small", "medium", "large", "custom"
    val cameraScale: Float = 0.26f, // fraction of screen width
    val cameraBorderWidth: Int = 3, // 0, 2, 4, 6 dp
    val cameraBorderColor: String = "#FF4B2B",
    val cameraMirrored: Boolean = true,
    val floatingBallEnabled: Boolean = true,
    val hideFloatingBallDuringRecording: Boolean = true,
    val hidePhoneControls: Boolean = true,
    val shakeToStop: Boolean = true,
    val videoSizePreset: String = "fullscreen", // "fullscreen", "youtube", "social", "square", "cinema", "tablet", "auto"
    val filenamePrefix: String = "ScreenPro_",
    val preserveSource: Boolean = true,
    val themeMode: String = "dark" // "dark", "light", "system"
)

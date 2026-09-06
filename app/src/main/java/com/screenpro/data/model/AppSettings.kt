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
    val cameraMode: String = "off", // "off", "facecam", "rear", "dual", "dual_only"
    val dualCameraLayout: String = "pip", // "pip", "split_horizontal", "split_vertical", "dual_bubbles"
    val cameraLensFacing: String = "front", // "front", "back"
    val cameraShape: String = "circle", // "circle", "rounded-square", "rectangle"
    val cameraPosition: String = "top-right", // "top-left", "top-right", "bottom-left", "bottom-right", "center", "custom"
    val cameraPositionX: Float = 0.75f, // 0.0 to 1.0
    val cameraPositionY: Float = 0.08f, // 0.0 to 1.0
    val cameraSize: String = "medium", // "small", "medium", "large", "custom"
    val cameraScale: Float = 0.26f, // fraction of screen width
    val cameraBorderWidth: Int = 3, // 0, 2, 4, 6 dp
    val cameraBorderColor: String = "#FF4B2B",
    val cameraMirrored: Boolean = true,
    // Secondary camera overlay configuration for Dual Camera modes
    val secondaryCameraShape: String = "circle",
    val secondaryCameraPositionX: Float = 0.08f,
    val secondaryCameraPositionY: Float = 0.08f,
    val secondaryCameraSize: String = "small",
    val secondaryCameraScale: Float = 0.20f,
    val secondaryCameraBorderWidth: Int = 3,
    val secondaryCameraBorderColor: String = "#00E5FF",
    val secondaryCameraMirrored: Boolean = false,
    val floatingBallEnabled: Boolean = true,
    val hideFloatingBallDuringRecording: Boolean = false,
    val hidePhoneControls: Boolean = true,
    val shakeToStop: Boolean = true,
    val audioBitrate: Int = 192_000,
    val audioSampleRate: Int = 48_000,
    val audioChannels: Int = 2,
    val videoSizePreset: String = "fullscreen", // "fullscreen", "youtube", "social", "square", "cinema", "tablet", "auto"
    val filenamePrefix: String = "ScreenPro_",
    val preserveSource: Boolean = true,
    val themeMode: String = "dark" // "dark", "light", "system"
)

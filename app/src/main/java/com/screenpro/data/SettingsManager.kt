package com.screenpro.data

import android.content.Context
import android.content.SharedPreferences
import com.screenpro.data.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("screenpro_preferences", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        return AppSettings(
            resolution = prefs.getString("resolution", "1080p") ?: "1080p",
            fps = prefs.getInt("fps", 60),
            bitrate = prefs.getString("bitrate", "high") ?: "high",
            countdown = prefs.getInt("countdown", 3),
            showTouches = prefs.getBoolean("showTouches", false),
            audioSource = prefs.getString("audioSource", "mic") ?: "mic",
            echoCancellation = prefs.getBoolean("echoCancellation", true),
            noiseSuppression = prefs.getBoolean("noiseSuppression", true),
            cameraEnabled = prefs.getBoolean("cameraEnabled", false),
            cameraShape = prefs.getString("cameraShape", "circle") ?: "circle",
            cameraPosition = prefs.getString("cameraPosition", "top-right") ?: "top-right",
            cameraPositionX = prefs.getFloat("cameraPositionX", 0.75f),
            cameraPositionY = prefs.getFloat("cameraPositionY", 0.08f),
            cameraSize = prefs.getString("cameraSize", "medium") ?: "medium",
            cameraScale = prefs.getFloat("cameraScale", 0.26f),
            cameraBorderWidth = prefs.getInt("cameraBorderWidth", 3),
            cameraBorderColor = prefs.getString("cameraBorderColor", "#FF4B2B") ?: "#FF4B2B",
            cameraMirrored = prefs.getBoolean("cameraMirrored", true),
            floatingBallEnabled = prefs.getBoolean("floatingBallEnabled", true),
            hideFloatingBallDuringRecording = prefs.getBoolean("hideFloatingBallDuringRecording", false),
            hidePhoneControls = prefs.getBoolean("hidePhoneControls", true),
            shakeToStop = prefs.getBoolean("shakeToStop", true),
            audioBitrate = prefs.getInt("audioBitrate", 192_000),
            audioSampleRate = prefs.getInt("audioSampleRate", 48_000),
            audioChannels = prefs.getInt("audioChannels", 2),
            videoSizePreset = prefs.getString("videoSizePreset", "fullscreen") ?: "fullscreen",
            filenamePrefix = prefs.getString("filenamePrefix", "ScreenPro_") ?: "ScreenPro_",
            preserveSource = prefs.getBoolean("preserveSource", true),
            themeMode = prefs.getString("themeMode", "dark") ?: "dark"
        )
    }

    fun updateSettings(newSettings: AppSettings) {
        prefs.edit().apply {
            putString("resolution", newSettings.resolution)
            putInt("fps", newSettings.fps)
            putString("bitrate", newSettings.bitrate)
            putInt("countdown", newSettings.countdown)
            putBoolean("showTouches", newSettings.showTouches)
            putString("audioSource", newSettings.audioSource)
            putBoolean("echoCancellation", newSettings.echoCancellation)
            putBoolean("noiseSuppression", newSettings.noiseSuppression)
            putBoolean("cameraEnabled", newSettings.cameraEnabled)
            putString("cameraShape", newSettings.cameraShape)
            putString("cameraPosition", newSettings.cameraPosition)
            putFloat("cameraPositionX", newSettings.cameraPositionX)
            putFloat("cameraPositionY", newSettings.cameraPositionY)
            putString("cameraSize", newSettings.cameraSize)
            putFloat("cameraScale", newSettings.cameraScale)
            putInt("cameraBorderWidth", newSettings.cameraBorderWidth)
            putString("cameraBorderColor", newSettings.cameraBorderColor)
            putBoolean("cameraMirrored", newSettings.cameraMirrored)
            putBoolean("floatingBallEnabled", newSettings.floatingBallEnabled)
            putBoolean("hideFloatingBallDuringRecording", newSettings.hideFloatingBallDuringRecording)
            putBoolean("hidePhoneControls", newSettings.hidePhoneControls)
            putBoolean("shakeToStop", newSettings.shakeToStop)
            putInt("audioBitrate", newSettings.audioBitrate)
            putInt("audioSampleRate", newSettings.audioSampleRate)
            putInt("audioChannels", newSettings.audioChannels)
            putString("videoSizePreset", newSettings.videoSizePreset)
            putString("filenamePrefix", newSettings.filenamePrefix)
            putBoolean("preserveSource", newSettings.preserveSource)
            putString("themeMode", newSettings.themeMode)
            apply()
        }
        _settings.value = newSettings
    }
}

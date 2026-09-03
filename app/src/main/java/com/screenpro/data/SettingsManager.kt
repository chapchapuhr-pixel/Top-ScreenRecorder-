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
            cameraMirrored = prefs.getBoolean("cameraMirrored", true),
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
            putBoolean("cameraMirrored", newSettings.cameraMirrored)
            putString("filenamePrefix", newSettings.filenamePrefix)
            putBoolean("preserveSource", newSettings.preserveSource)
            putString("themeMode", newSettings.themeMode)
            apply()
        }
        _settings.value = newSettings
    }
}

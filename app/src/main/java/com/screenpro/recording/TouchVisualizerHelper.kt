package com.screenpro.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

object TouchVisualizerHelper {
    private const val TAG = "TouchVisualizerHelper"
    private const val SHOW_TOUCHES = "show_touches"
    private var originalShowTouches: Int? = null

    /**
     * Checks if the app has permission to write system settings.
     * Required to toggle Android OS native touch pointer visualizer.
     */
    fun canWriteSystemSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }

    /**
     * Launches the system settings screen for the user to grant WRITE_SETTINGS permission.
     */
    fun openWriteSettingsPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open write settings permission", e)
            }
        }
    }

    /**
     * Checks whether Android's system "show touches" is currently enabled.
     */
    fun isSystemTouchesCurrentlyEnabled(context: Context): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, SHOW_TOUCHES, 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Automatically enables native Android OS touch visualizer dots when recording starts.
     * Caches the user's prior setting so it can be restored when recording finishes.
     */
    fun enableTouchesForRecording(context: Context): Boolean {
        if (!canWriteSystemSettings(context)) {
            Log.d(TAG, "Cannot write system settings; user hasn't granted WRITE_SETTINGS permission")
            return false
        }
        return try {
            if (originalShowTouches == null) {
                originalShowTouches = Settings.System.getInt(context.contentResolver, SHOW_TOUCHES, 0)
            }
            Settings.System.putInt(context.contentResolver, SHOW_TOUCHES, 1)
            Log.d(TAG, "Native touch visualizer enabled for recording")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable native show touches", e)
            false
        }
    }

    /**
     * Restores Android OS system touches setting back to what it was before recording started.
     */
    fun restoreTouchesAfterRecording(context: Context) {
        if (!canWriteSystemSettings(context)) return
        val original = originalShowTouches ?: return
        try {
            Settings.System.putInt(context.contentResolver, SHOW_TOUCHES, original)
            Log.d(TAG, "Native touch visualizer restored to prior state: $original")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore show touches", e)
        } finally {
            originalShowTouches = null
        }
    }
}

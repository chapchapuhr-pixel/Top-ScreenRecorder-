plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Automatically restore debug.keystore from debug.keystore.base64 if missing in CI/cloned repo
val debugKeystore = file("debug.keystore")
val base64DebugKeystore = file("debug.keystore.base64")
if (!debugKeystore.exists() && base64DebugKeystore.exists()) {
    try {
        val bytes = java.util.Base64.getDecoder().decode(base64DebugKeystore.readText().trim())
        debugKeystore.writeBytes(bytes)
    } catch (_: Exception) {
    }
}


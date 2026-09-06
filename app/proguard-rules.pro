# ProGuard / R8 rules for Free Screen Recorder

# Jetpack Compose
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# Kotlin Coroutines & Serialization
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# CameraX & Media
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep models and entities
-keep class com.screenpro.data.model.** { *; }
-keepclassmembers class com.screenpro.data.model.** { *; }

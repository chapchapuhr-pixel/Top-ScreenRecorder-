package com.screenpro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkPrimary = Color(0xFFFF4B2B)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkPrimaryContainer = Color(0xFF3B1510)
val DarkOnPrimaryContainer = Color(0xFFFFDAD4)

val DarkSecondary = Color(0xFFFF6F59)
val DarkBackground = Color(0xFF0A0A0A)
val DarkSurface = Color(0xFF141414)
val DarkSurfaceVariant = Color(0xFF1F1F1F)
val DarkOutline = Color(0xFF2E2E2E)
val DarkOnSurface = Color(0xFFE4E4E4)
val DarkOnSurfaceVariant = Color(0xFFA0A0A0)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    outline = DarkOutline,
    onBackground = DarkOnSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFE03818),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD4),
    onPrimaryContainer = Color(0xFF3B1510),
    secondary = Color(0xFF775651),
    background = Color(0xFFFBFBFB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEEEEE),
    outline = Color(0xFFD0D0D0),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF555555)
)

@Composable
fun ScreenProTheme(
    themeMode: String = "dark",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

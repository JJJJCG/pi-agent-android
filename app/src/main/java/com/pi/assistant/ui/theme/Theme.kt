package com.pi.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.pi.assistant.data.prefs.ThemeMode

private val LightColors = lightColorScheme(
    primary = PiIndigo,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = PiTeal,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = PiLightBackground,
    onBackground = PiLightOnSurface,
    surface = PiLightSurface,
    onSurface = PiLightOnSurface,
    surfaceVariant = PiLightSurfaceVariant,
    onSurfaceVariant = PiLightOnSurfaceVariant,
    error = PiError,
)

private val DarkColors = darkColorScheme(
    primary = PiIndigoDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF0E1330),
    secondary = PiTealDark,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF04231C),
    background = PiDarkBackground,
    onBackground = PiDarkOnSurface,
    surface = PiDarkSurface,
    onSurface = PiDarkOnSurface,
    surfaceVariant = PiDarkSurfaceVariant,
    onSurfaceVariant = PiDarkOnSurfaceVariant,
    error = PiErrorDark,
)

@Composable
fun PiTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = PiTypography,
        content = content,
    )
}

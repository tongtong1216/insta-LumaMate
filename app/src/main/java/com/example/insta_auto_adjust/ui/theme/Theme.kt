package com.example.insta_auto_adjust.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    secondary = AccentGreen,
    background = SurfaceLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceMuted,
    onPrimary = SurfaceLight,
    onSecondary = SurfaceLight,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueDark,
    secondary = AccentGreen,
)

@Composable
fun InstaAutoAdjustTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}

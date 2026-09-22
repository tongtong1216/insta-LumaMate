package com.example.insta_auto_adjust.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightPilotColorScheme = darkColorScheme(
    primary = PilotYellow,
    onPrimary = PilotInk,
    primaryContainer = PilotYellowSoft,
    onPrimaryContainer = PilotInk,
    secondary = PilotWhite,
    onSecondary = PilotInk,
    background = PilotBlack,
    onBackground = PilotWhite,
    surface = PilotBlackSoft,
    onSurface = PilotWhite,
    surfaceVariant = PilotWhite,
    onSurfaceVariant = PilotInk,
    outline = PilotLine,
    error = PilotRed
)

@Composable
@Suppress("UNUSED_PARAMETER")
fun InstaAutoAdjustTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = LightPilotColorScheme,
        typography = Typography,
        content = content
    )
}

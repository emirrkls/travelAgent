package com.emirrkls.phokarta.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Kept as source-compatible aliases while the visual language moves to blue/mist.
val Coral = Color(0xFF2563EB)
val CoralDark = Color(0xFF1D4ED8)
val Ink = Color(0xFF172033)
val Sand = Color(0xFFF7FAFF)
val Sage = Color(0xFF0F6B78)
val Mist = Color(0xFFE5F1F8)
val Muted = Color(0xFF667085)

private val LightColors = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF102A56),
    secondary = Sage,
    onSecondary = Color.White,
    secondaryContainer = Mist,
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF3F8),
    onSurfaceVariant = Muted,
    outline = Color(0xFFCBD5E1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC1FF),
    onPrimary = Color(0xFF08265A),
    primaryContainer = Color(0xFF173C75),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFF86D3E1),
    onSecondary = Color(0xFF00363E),
    secondaryContainer = Color(0xFF164E59),
    onSecondaryContainer = Color(0xFFCCF5FB),
    background = Color(0xFF101722),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF172033),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF222D40),
    onSurfaceVariant = Color(0xFFBAC6D6),
    outline = Color(0xFF8492A6),
)

@Composable
fun PhokartaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TravelTypography,
        shapes = TravelShapes,
        content = content,
    )
}

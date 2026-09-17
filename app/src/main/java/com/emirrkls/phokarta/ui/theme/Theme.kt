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

// Source-compatible aliases. New UI should consume Material semantic roles.
val Coral = Color(0xFF5EB6EC)
val CoralDark = Color(0xFF3B95CC)
val Ink = Color(0xFF17212B)
val Sand = Color(0xFFF8FBFD)
val Sage = Color(0xFF19736F)
val Mist = Color(0xFFDDF2FF)
val Muted = Color(0xFF65717D)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5EB6EC),
    onPrimary = Ink,
    primaryContainer = Color(0xFFDDF2FF),
    onPrimaryContainer = Ink,
    secondary = Sage,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7F5FF),
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1F6F9),
    onSurfaceVariant = Muted,
    outline = Color(0xFFDCE5EB),
    outlineVariant = Color(0xFFEAF0F4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FBFD),
    surfaceContainer = Color(0xFFF1F6F9),
    surfaceContainerHigh = Color(0xFFEAF0F4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ECDF4),
    onPrimary = Color(0xFF10202B),
    primaryContainer = Color(0xFF213A49),
    onPrimaryContainer = Color(0xFFDDF2FF),
    secondary = Color(0xFF86D3E1),
    onSecondary = Color(0xFF00363E),
    secondaryContainer = Color(0xFF164E59),
    onSecondaryContainer = Color(0xFFCCF5FB),
    background = Color(0xFF101820),
    onBackground = Color(0xFFF4F8FA),
    surface = Color(0xFF172330),
    onSurface = Color(0xFFF4F8FA),
    surfaceVariant = Color(0xFF1D2B38),
    onSurfaceVariant = Color(0xFFAAB7C0),
    outline = Color(0xFF30414E),
    outlineVariant = Color(0xFF253541),
    surfaceContainerLowest = Color(0xFF121C25),
    surfaceContainerLow = Color(0xFF172330),
    surfaceContainer = Color(0xFF1D2B38),
    surfaceContainerHigh = Color(0xFF243440),
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

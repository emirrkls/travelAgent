package com.emirrkls.travelagent.ui.theme

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

val Coral = Color(0xFFE86F51)
val CoralDark = Color(0xFFB9432E)
val Ink = Color(0xFF20231F)
val Sand = Color(0xFFFFF8F1)
val Sage = Color(0xFF426B5B)
val Mist = Color(0xFFE9F0EC)
val Muted = Color(0xFF727770)

private val LightColors = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDACE),
    onPrimaryContainer = Color(0xFF3B0A00),
    secondary = Sage,
    onSecondary = Color.White,
    secondaryContainer = Mist,
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3F0EB),
    onSurfaceVariant = Muted,
    outline = Color(0xFFD8D4CD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59F),
    onPrimary = Color(0xFF5C190C),
    primaryContainer = Color(0xFF6E2B1E),
    onPrimaryContainer = Color(0xFFFFDACE),
    secondary = Color(0xFFA9D4C1),
    onSecondary = Color(0xFF11372A),
    secondaryContainer = Color(0xFF294B3F),
    onSecondaryContainer = Color(0xFFC5F0DC),
    background = Color(0xFF181A18),
    onBackground = Color(0xFFE4E3DF),
    surface = Color(0xFF20231F),
    onSurface = Color(0xFFE4E3DF),
    surfaceVariant = Color(0xFF2B302C),
    onSurfaceVariant = Color(0xFFC4C8C2),
    outline = Color(0xFF8C928C),
)

@Composable
fun TravelAgentTheme(
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

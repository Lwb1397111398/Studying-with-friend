package com.studyfriend.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = MainBrown,
    onPrimary = PaperLight,
    primaryContainer = Highlight,
    onPrimaryContainer = InkBrown,
    secondary = SoftGreen,
    onSecondary = PaperLight,
    background = PaperLight,
    onBackground = InkBrown,
    surface = PaperWarm,
    onSurface = InkBrown,
    surfaceVariant = Highlight,
    onSurfaceVariant = MainBrownDark,
    error = ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = MainBrownLight,
    onPrimary = PaperDark,
    primaryContainer = HighlightDark,
    onPrimaryContainer = InkLight,
    secondary = SoftGreenDark,
    onSecondary = PaperDark,
    background = PaperDark,
    onBackground = InkLight,
    surface = PaperDarkWarm,
    onSurface = InkLight,
    surfaceVariant = HighlightDark,
    onSurfaceVariant = MainBrownLight,
    error = ErrorRed,
)

@Composable
fun StudyFriendTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ReadingTypography,
        content = content,
    )
}

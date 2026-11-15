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
    secondaryContainer = Highlight.copy(alpha = 0.45f),
    onSecondaryContainer = InkBrown,
    tertiary = MainBrownDark,
    onTertiary = PaperLight,
    background = PaperLight,
    onBackground = InkBrown,
    surface = PaperLight,
    onSurface = InkBrown,
    onSurfaceVariant = MainBrownDark,
    surfaceVariant = PaperWarm,
    surfaceContainerLowest = PaperLight,
    surfaceContainerLow = PaperWarm,
    surfaceContainer = CardLight,
    surfaceContainerHigh = CardLightHigh,
    surfaceContainerHighest = CardLightHigh,
    outlineVariant = OutlineLight,
    error = ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = MainBrownLight,
    onPrimary = PaperDark,
    primaryContainer = HighlightDark,
    onPrimaryContainer = InkLight,
    secondary = SoftGreenDark,
    onSecondary = PaperDark,
    secondaryContainer = HighlightDark.copy(alpha = 0.55f),
    onSecondaryContainer = InkLight,
    tertiary = MainBrownLight,
    onTertiary = PaperDark,
    background = PaperDark,
    onBackground = InkLight,
    surface = PaperDark,
    onSurface = InkLight,
    onSurfaceVariant = MainBrownLight,
    surfaceVariant = PaperDarkWarm,
    surfaceContainerLowest = PaperDark,
    surfaceContainerLow = PaperDarkWarm,
    surfaceContainer = CardDark,
    surfaceContainerHigh = CardDarkHigh,
    surfaceContainerHighest = CardDarkHigh,
    outlineVariant = OutlineDark,
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

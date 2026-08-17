package com.aus.deutschflow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Every token the app renders is named here.
 *
 * darkColorScheme fills anything left out from Material's baseline palette, and that
 * palette is purple - so the tokens this scheme did not mention were quietly not
 * this app's colours at all. The containers and the surfaceContainer ramp are here
 * because components reach for them without being asked.
 */
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnSurface,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    // Secondary is the calm cyan: the transcription accent. Status colours carry
    // the rest of the semantics (tertiary green, error red, warning amber).
    secondary = SecondaryCyan,
    onSecondary = Color(0xFF062B33),
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = TertiaryGreen,
    onTertiary = Background,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = ErrorRed,
    onError = OnSurface,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer
)

@Composable
fun DeutschflowTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}

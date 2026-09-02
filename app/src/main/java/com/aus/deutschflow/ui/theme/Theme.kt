package com.aus.deutschflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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

/**
 * The same roles, filled from the light palette.
 *
 * Every slot the dark scheme names is named here too, for the same reason: an
 * unnamed slot is Material's purple, and a light scheme is *more* exposed to that
 * than a dark one because its defaults are pale enough to look plausible.
 */
private val LightColorScheme = lightColorScheme(
    primary = LightPrimaryBlue,
    // White on the light primary, not the light theme's near-black: the button is
    // a saturated blue fill either way, and it is the fill the label sits on.
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondaryCyan,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiaryGreen,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightErrorRed,
    onError = Color(0xFFFFFFFF),
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer
)

/**
 * The tokens this app has that Material's ColorScheme has no slot for.
 *
 * These used to be read straight off [Color.kt] as top-level `val`s - sixty call
 * sites naming `AzureGlow` or `GlassFill` directly. That is exactly as themeable
 * as a hardcoded hex, which is why the app could only ever be dark: a `val` does
 * not know which scheme is in force. Reading them through a CompositionLocal is
 * what makes the light theme possible at all.
 */
@Immutable
data class AppColors(
    /** The calm cyan: the listening state and every transcription accent. */
    val azureGlow: Color,
    /** The electric blue it lands on: gradients and focused edges. */
    val azureDeep: Color,
    /** The default card and input fill. */
    val glassFill: Color,
    /** One step nearer, for tiles inside a card. */
    val glassFillRaised: Color,
    /** Quiet but readable body text; measured, never alpha over the ground. */
    val onSurfaceMuted: Color,
    /** Success. Also the "correct word" colour on Practice. */
    val tertiaryGreen: Color,
    val warningAmber: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** The lighter end of the ramp, for gradients that stay inside the brand. */
    val primaryBlueLight: Color
)

private val DarkAppColors = AppColors(
    azureGlow = AzureGlow,
    azureDeep = AzureDeep,
    glassFill = GlassFill,
    glassFillRaised = GlassFillRaised,
    onSurfaceMuted = OnSurfaceMuted,
    tertiaryGreen = TertiaryGreen,
    warningAmber = WarningAmber,
    warningContainer = WarningContainer,
    onWarningContainer = OnWarningContainer,
    primaryBlueLight = PrimaryBlueLight
)

private val LightAppColors = AppColors(
    azureGlow = LightAzureGlow,
    azureDeep = LightAzureDeep,
    glassFill = LightGlassFill,
    glassFillRaised = LightGlassFillRaised,
    onSurfaceMuted = LightOnSurfaceMuted,
    tertiaryGreen = LightTertiaryGreen,
    warningAmber = LightWarningAmber,
    warningContainer = LightWarningContainer,
    onWarningContainer = LightOnWarningContainer,
    primaryBlueLight = LightPrimaryBlueLight
)

/**
 * Defaults to the dark set so a composable rendered outside [DeutschflowTheme] -
 * a preview, a test - shows the app's colours rather than throwing.
 */
val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

/** `AppTheme.colors.azureGlow`, alongside `MaterialTheme.colorScheme.primary`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

@Composable
fun DeutschflowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Read once, here, so no screen has to reach for a Context to find out whether
    // it is allowed to move.
    val reducedMotion = systemPrefersReducedMotion()

    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalAppColors provides if (darkTheme) DarkAppColors else LightAppColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}

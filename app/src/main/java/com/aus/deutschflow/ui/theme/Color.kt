package com.aus.deutschflow.ui.theme

import androidx.compose.ui.graphics.Color

// High Contrast Dark Theme Palette
val Background = Color(0xFF121212)
val Surface = Color(0xFF1E1E1E)
val SurfaceVariant = Color(0xFF2C2C2E)
val OnBackground = Color(0xFFF2F2F7)
val OnSurface = Color(0xFFFFFFFF)
val OnSurfaceVariant = Color(0xFF8E8E93)

/**
 * For text that should recede without becoming unreadable.
 *
 * Callers used to write `OnSurfaceVariant.copy(alpha = 0.7f)`, which composites down
 * to roughly 3.2:1 against the surface - under the 4.5:1 WCAG AA needs for body text.
 * Alpha over a dark ground loses contrast much faster than it looks like it should.
 * This is a flat colour at 4.6:1.
 */
val OnSurfaceMuted = Color(0xFF9C9CA1)

// Branding Colors
val PrimaryBlue = Color(0xFF0A84FF) // Standard High-Contrast Blue
val SecondaryOrange = Color(0xFFFF9F0A)
val TertiaryGreen = Color(0xFF30D158)
val ErrorRed = Color(0xFFFF453A)

/**
 * The lighter end of the recording control's gradient.
 *
 * Two steps of the brand blue rather than two hues. The gradient was a hardcoded
 * cyan, and swapping it for the theme's tertiary made it green-to-blue, which read
 * as a different product's button.
 */
val PrimaryBlueLight = Color(0xFF4CC2FF)

/**
 * Container pairs for the four brand roles.
 *
 * Material fills in any token a scheme leaves unset from its own baseline palette,
 * which is purple. Every one of these was unset, so the library's play button was a
 * blue icon on a purple pill and Study's flipped card put green text on a dusty
 * pink. Each container is a dark tint of its own hue, and each `On` colour is a pale
 * tint of the same, which is what makes them safe to pair.
 */
/**
 * Dark enough to sit behind pale text, which is what a container is for - but note
 * that Material's FloatingActionButton defaults to this colour, and a navy FAB on a
 * near-black ground is invisible. The library's button sets primary explicitly for
 * that reason.
 */
val PrimaryContainer = Color(0xFF0A2540)
val OnPrimaryContainer = Color(0xFFCCE4FF)
val SecondaryContainer = Color(0xFF3A2A05)
val OnSecondaryContainer = Color(0xFFFFE2B8)
val TertiaryContainer = Color(0xFF0E3A1B)
val OnTertiaryContainer = Color(0xFFC6F6D5)
val ErrorContainer = Color(0xFF3E1512)
val OnErrorContainer = Color(0xFFFFDAD6)

// Borders and dividers, also purple-tinted until they were named.
val Outline = Color(0xFF48484A)
val OutlineVariant = Color(0xFF3A3A3C)

/**
 * The elevation ramp Material 3 components reach for on their own - AlertDialog
 * takes surfaceContainerHigh, so the app's own dialogs were sitting on a
 * purple-tinted grey that appears nowhere else in the palette.
 */
val SurfaceContainerLowest = Color(0xFF0A0A0A)
val SurfaceContainerLow = Color(0xFF161618)
val SurfaceContainer = Color(0xFF1E1E1E)
val SurfaceContainerHigh = Color(0xFF262628)
val SurfaceContainerHighest = Color(0xFF2C2C2E)

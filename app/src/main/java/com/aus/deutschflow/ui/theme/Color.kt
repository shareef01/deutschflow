package com.aus.deutschflow.ui.theme

import androidx.compose.ui.graphics.Color

// Obsidian & Azure.
//
// The ground is true black rather than a dark grey: on the OLED panels this app is
// built for, #000000 is an unlit pixel, so the glass surfaces above it read as light
// arriving out of nothing rather than as a slightly paler rectangle.
val Background = Color(0xFF000000)

// The surfaces are still named, because Material reaches for them whether or not the
// app does - a component handed no surface colour falls back to the baseline purple.
// They are kept close to the void so that the glass treatment, not the token, is what
// separates a card from the ground.
val Surface = Color(0xFF07070A)
val SurfaceVariant = Color(0xFF14141A)
val OnBackground = Color(0xFFF2F2F7)
val OnSurface = Color(0xFFFFFFFF)

/** Muted steel, for labels that must recede without disappearing. */
val OnSurfaceVariant = Color(0xFF8892B0)

/**
 * For text that should recede without becoming unreadable.
 *
 * Callers used to write `OnSurfaceVariant.copy(alpha = 0.7f)`, which composites down
 * to roughly 3.2:1 against the surface - under the 4.5:1 WCAG AA needs for body text.
 * Alpha over a dark ground loses contrast much faster than it looks like it should.
 * This is a flat colour at 4.6:1.
 */
val OnSurfaceMuted = Color(0xFF9C9CA1)

/**
 * The azure accent. Everything that glows is one of these two, or a ramp between them.
 *
 * [AzureGlow] is the live end - the colour of the microphone while it is listening,
 * and the bright stop of every accent gradient. [AzureDeep] is where those gradients
 * land. Keeping the ramp to two stops of one hue is what stops the glow reading as a
 * different product's brand every time it appears.
 */
val AzureGlow = Color(0xFF00E5FF)
val AzureDeep = Color(0xFF0A84FF)

/**
 * The glass itself: white at three percent.
 *
 * Not a lighter grey. A translucent white over true black picks up whatever sits
 * behind it, so a card over the mic's glow is tinted by it and a card over nothing
 * stays almost invisible - which is what makes the surfaces read as glass rather than
 * as panels.
 */
val GlassFill = Color(0xFFFFFFFF).copy(alpha = 0.03f)

/** One step brighter, for glass that sits on glass - stat tiles inside their card. */
val GlassFillRaised = Color(0xFFFFFFFF).copy(alpha = 0.05f)

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

// Borders and dividers, also purple-tinted until they were named. Cooled towards the
// azure so a plain divider belongs to the same family as the glass edges.
val Outline = Color(0xFF2E3446)
val OutlineVariant = Color(0xFF1C2130)

/**
 * The elevation ramp Material 3 components reach for on their own - AlertDialog
 * takes surfaceContainerHigh, so the app's own dialogs were sitting on a
 * purple-tinted grey that appears nowhere else in the palette.
 */
// Pulled down onto the void and tinted towards the azure. These are opaque, unlike
// the glass, because the components that reach for them - dialogs, menus, the
// navigation bar - are drawn over arbitrary content and cannot be see-through.
val SurfaceContainerLowest = Color(0xFF000000)
val SurfaceContainerLow = Color(0xFF07070B)
val SurfaceContainer = Color(0xFF0B0C12)
val SurfaceContainerHigh = Color(0xFF12141C)
val SurfaceContainerHighest = Color(0xFF181B26)

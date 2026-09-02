package com.aus.deutschflow.ui.theme

import androidx.compose.ui.graphics.Color

// DeutschFlow dark palette — refined.
//
// The ground is a deep blue-black, not pure #000: on OLED it is still nearly an
// unlit pixel, but a card or divider can now sit *below* the ground's value if it
// needs to recede, and large empty areas read as a surface rather than a hole.
// Colour is reserved for actions, progress, status and what is being learned —
// never for decoration.

val Background = Color(0xFF0A0E16)

// The surface ramp is opaque and cool-tinted. Cards separate from the background
// by a small step in value plus a hairline edge — no glow, no glass — so content
// is what catches the eye. Material's own components (dialogs, menus, sheets)
// reach for this ramp on their own.
val Surface = Color(0xFF0E131D)
val SurfaceVariant = Color(0xFF18202F)
val OnBackground = Color(0xFFF2F5FA)
val OnSurface = Color(0xFFFFFFFF)

/** Muted steel-blue, for labels that must recede without disappearing. */
val OnSurfaceVariant = Color(0xFF98A2B3)

/**
 * For text that should recede without becoming unreadable — a flat colour, never
 * alpha over a dark ground (alpha loses contrast far faster than it looks).
 *
 * Measured rather than judged. At 0xFF6E7889 this was 4.33:1 on the ground and
 * 3.94:1 on a card, against the 4.5:1 WCAG asks for body text - so the one colour
 * whose whole job is "quiet but readable" was neither. Ten points lighter on each
 * channel carries it to 4.98:1 and 4.53:1 while staying the same steel blue.
 */
val OnSurfaceMuted = Color(0xFF788293)

/**
 * The accent ramp. Everything "live" is one of these two.
 *
 * [AzureGlow] is the calm cyan — the listening state and every transcription
 * accent. [AzureDeep] is the electric blue it lands on — the primary action
 * colour. Two stops of one ramp keeps the app reading as one product.
 */
val AzureGlow = Color(0xFF4EC9E8)
val AzureDeep = Color(0xFF0A84FF)

/**
 * The default card and input fill — opaque now, from the elevation ramp.
 *
 * The old glass fill (white at 3% over black) left cards almost invisible unless
 * a gradient border outlined them; the border then did all the separating and
 * everything read as outlined rather than surfaced. An opaque fill at
 * [SurfaceContainer] separates by value, so the hairline edge can stay quiet.
 */
val GlassFill = Color(0xFF131926)

/** One step brighter, for surfaces that sit on surfaces — tiles inside a card. */
val GlassFillRaised = Color(0xFF1A2233)

// Branding and status colours. One blue, one cyan, and one colour each for
// success, warning and error — the status set is what signals state, so it must
// never be recycled for decoration.
val PrimaryBlue = Color(0xFF0A84FF)
val SecondaryCyan = Color(0xFF43C6DE)
val TertiaryGreen = Color(0xFF30D158)
val WarningAmber = Color(0xFFFFB340)
val ErrorRed = Color(0xFFFF453A)

/**
 * The lighter end of the accent ramp, for gradients that stay inside the brand
 * (the Study progress bar, the Practice meter).
 */
val PrimaryBlueLight = Color(0xFF4CC2FF)

/**
 * Container pairs for the brand roles: each container a dark tint of its own hue,
 * each `On` colour a pale tint of the same, which is what makes them safe to
 * pair. Material fills any token a scheme leaves unset from its baseline palette
 * (purple) — every one of these is named on purpose.
 */
val PrimaryContainer = Color(0xFF0A2540)
val OnPrimaryContainer = Color(0xFFCCE4FF)
val SecondaryContainer = Color(0xFF0B2A33)
val OnSecondaryContainer = Color(0xFFBFEBF5)
val TertiaryContainer = Color(0xFF0E3A1B)
val OnTertiaryContainer = Color(0xFFC6F6D5)
val ErrorContainer = Color(0xFF3E1512)
val OnErrorContainer = Color(0xFFFFDAD6)
val WarningContainer = Color(0xFF3A2A05)
val OnWarningContainer = Color(0xFFFFE2B8)

// Borders and dividers: cool grey-blues from the same family as the ground.
//
// Raised to carry the separation the surface ramp does not. Measured, a card sits
// 1.10:1 above the ground - imperceptible - so the edge is what has to say where the
// card ends, and at 1.38:1 it was saying nothing either. 3:1 is what WCAG 1.4.11
// asks of a boundary that identifies a component; the divider is quieter at 2:1,
// since it separates rather than identifies.
val Outline = Color(0xFF565F70)
val OutlineVariant = Color(0xFF3D4554)

/**
 * The elevation ramp Material 3 components reach for on their own. Opaque (drawn
 * over arbitrary content, unlike the cards), each one a small step up from the
 * last, so dialogs, menus and the navigation bar separate by value.
 */
val SurfaceContainerLowest = Color(0xFF0A0E16)
val SurfaceContainerLow = Color(0xFF0E131D)
val SurfaceContainer = Color(0xFF131926)
val SurfaceContainerHigh = Color(0xFF18202F)
val SurfaceContainerHighest = Color(0xFF1E2739)

// ---------------------------------------------------------------------------
// The light palette
// ---------------------------------------------------------------------------
//
// Both apps were dark-only, and the README implied the theme adapted. It does
// now: the system's setting picks the scheme, on both platforms, and there is no
// in-app override to keep in sync with it.
//
// This is not the dark palette inverted. Inverting a dark theme gives you grey
// text on white and neon accents nobody can read - a cyan that glows against a
// blue-black ground measures 1.9:1 against white. Every value here was chosen for
// its hue and then measured: `python tools/contrast.py` runs the same 28 pairings
// against both palettes and exits non-zero if either falls below its threshold.
//
// The figure-ground relationship flips, which is the part worth stating. In the
// dark theme the ground is the darkest thing and cards step *up* from it; in the
// light theme the ground is a tinted grey and cards step *up* to white. Both keep
// "the card is nearer than the ground", which is what the design depends on.

val LightBackground = Color(0xFFF5F7FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE4E9F0)
val LightOnBackground = Color(0xFF101623)
val LightOnSurface = Color(0xFF0A0E16)

/** The same steel blue, darkened until it clears 4.5:1 on white and on the ground. */
val LightOnSurfaceVariant = Color(0xFF4A5468)
val LightOnSurfaceMuted = Color(0xFF5C6678)

/**
 * The accent ramp, re-derived rather than reused.
 *
 * [AzureGlow] at 0xFF4EC9E8 measures 1.68:1 on white - it is a colour designed to
 * glow out of a dark ground, and on a light one it is close to invisible. The
 * light theme keeps the hue and takes it down the ramp until it reads as text.
 */
val LightAzureGlow = Color(0xFF0A7288)
val LightAzureDeep = Color(0xFF0B62C4)

/** Cards are white on a tinted ground, the mirror of white-on-black-ground. */
val LightGlassFill = Color(0xFFFFFFFF)
val LightGlassFillRaised = Color(0xFFF0F3F8)

val LightPrimaryBlue = Color(0xFF0B62C4)
val LightSecondaryCyan = Color(0xFF0E7490)
val LightTertiaryGreen = Color(0xFF167A3C)
val LightWarningAmber = Color(0xFF8A5A00)
val LightErrorRed = Color(0xFFC3271C)
val LightPrimaryBlueLight = Color(0xFF3D8FE0)

// Containers invert: a pale tint of the hue, with a dark on-colour of the same.
val LightPrimaryContainer = Color(0xFFD6E7FB)
val LightOnPrimaryContainer = Color(0xFF06305C)
val LightSecondaryContainer = Color(0xFFD2EEF5)
val LightOnSecondaryContainer = Color(0xFF06333D)
val LightTertiaryContainer = Color(0xFFD3F2DE)
val LightOnTertiaryContainer = Color(0xFF0A3D1E)
val LightErrorContainer = Color(0xFFFBDDDA)
val LightOnErrorContainer = Color(0xFF5C1710)
val LightWarningContainer = Color(0xFFFBEBCB)
val LightOnWarningContainer = Color(0xFF4A3200)

/** Same reasoning as the dark outline: 3:1 to identify, quieter to merely divide. */
val LightOutline = Color(0xFF7A8598)
val LightOutlineVariant = Color(0xFFC3CBD8)

val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFAFBFD)
val LightSurfaceContainer = Color(0xFFF5F7FA)
val LightSurfaceContainerHigh = Color(0xFFEFF2F7)
val LightSurfaceContainerHighest = Color(0xFFE8EDF4)

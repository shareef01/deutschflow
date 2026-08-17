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
 */
val OnSurfaceMuted = Color(0xFF6E7889)

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
val Outline = Color(0xFF232C3D)
val OutlineVariant = Color(0xFF1A2231)

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

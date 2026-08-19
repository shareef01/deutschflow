package com.aus.deutschflow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One rounding scale for the whole app.
 *
 * A screen picks a role — small, medium, large — rather than a number, and
 * Material's own components pick the same ones. Rounded, but not pill-shaped
 * everywhere: cards are 16dp, small controls 12dp, and only the deliberate
 * circular controls use a pill.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * The pill used by circular controls (the recording button's disc, the empty-state
 * medallion).
 *
 * A percentage, not a dp: the radius has to stay half the height for the ends to be
 * true semicircles, and the controls it is used on are not all the same height.
 */
val PillShape = RoundedCornerShape(percent = 50)

/**
 * One height for action buttons, so a row of them lines up.
 *
 * Comfortably above the 48dp minimum tap target: these are the primary actions on
 * their screens and they were already being hand-set to 56 and 64 in different files.
 */
val ActionButtonHeight = 56.dp

/**
 * The disc behind an empty state's icon, and the icon inside it.
 *
 * Kept as a pair: the icon is a little under half the disc, and changing one
 * without the other is what makes the medallion look either hollow or crowded.
 */
val EmptyStateMedallionSize = 144.dp
val EmptyStateIconSize = 68.dp

/**
 * The live audio waveform's height.
 *
 * A component dimension, not a gap - it briefly borrowed Spacing.xxl, which is the
 * right number for the wrong reason and would have drifted the moment the spacing
 * scale was tuned.
 */
val WaveformHeight = 40.dp

/**
 * The transcript card's floor, so it holds its shape before any speech arrives and
 * the layout does not jump the moment the first word lands.
 */
val TranscriptCardMinHeight = 200.dp

/** The record button, and the icon inside it. Kept as a pair, like the medallion. */
val RecordButtonSize = 100.dp
val RecordIconSize = 40.dp

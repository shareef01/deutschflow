package com.aus.deutschflow.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale.
 *
 * Every screen had been picking its own numbers - 8, 12, 16, 20, 24, 32, 40 and 48
 * all appeared, often two of them for the same job on different screens. A short
 * scale means the rhythm is consistent without anyone having to remember it.
 */
object Spacing {
    /** Between an icon and its label. */
    val xs = 4.dp

    /** Between tightly related items in the same block. */
    val sm = 8.dp

    /** The default gap, and the screen's side margin. */
    val md = 16.dp

    /** Between blocks that belong to different things. */
    val lg = 24.dp

    /** Above a new section heading. */
    val xl = 32.dp

    /**
     * The margin around a screen that holds one centred block - the empty states.
     * Wider than [md] on purpose: it is what stops a centred paragraph running the
     * full width of a phone and reading as a wall.
     */
    val xxl = 40.dp

    /**
     * Clears a bottom-anchored control, so the last row of a scrolling list is not
     * trapped underneath it.
     */
    val bottomActionClearance = 96.dp
}

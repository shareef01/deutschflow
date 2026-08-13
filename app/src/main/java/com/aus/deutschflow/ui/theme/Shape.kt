package com.aus.deutschflow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One rounding scale for the whole app.
 *
 * The screens had been hand-rolling RoundedCornerShape at 12, 16, 20 and 24dp, so
 * cards sitting next to each other were rounded differently for no reason. Naming
 * the scale here means a component picks a role - small, medium, large - rather than
 * a number, and Material's own components pick the same ones.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * The pill used by every primary and secondary action button.
 *
 * A percentage, not a dp: the radius has to stay half the height for the ends to be
 * true semicircles, and the buttons it is used on are not all the same height.
 */
val PillShape = RoundedCornerShape(percent = 50)

/**
 * One height for action buttons, so a row of them lines up.
 *
 * Comfortably above the 48dp minimum tap target: these are the primary actions on
 * their screens and they were already being hand-set to 56 and 64 in different files.
 */
val ActionButtonHeight = 56.dp

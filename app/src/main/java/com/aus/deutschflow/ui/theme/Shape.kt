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

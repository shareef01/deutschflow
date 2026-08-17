package com.aus.deutschflow.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aus.deutschflow.ui.theme.AzureDeep
import com.aus.deutschflow.ui.theme.AzureGlow
import com.aus.deutschflow.ui.theme.GlassFillRaised
import com.aus.deutschflow.ui.theme.pressScale
import com.aus.deutschflow.ui.theme.rememberBreath

/**
 * The recording control.
 *
 * One calm disc: an opaque fill, a hairline edge, the icon upright. Idle it sits
 * quiet in the accent blue; listening, the edge and fill turn up to the cyan and a
 * single ring pulses outside the disc — the one allowed pulse in the app, and only
 * while the microphone is actually open. The old version (a rotating sweep-gradient
 * mesh inside a radial halo) lit up the whole screen; the recording state no longer
 * needs to be readable from across a room, it needs to be readable without
 * competing with the transcript above it.
 *
 * The whole thing is one selectable target with a press-scale, so it reports itself
 * once to a screen reader instead of as a stack of decorative boxes.
 */
@Composable
fun OracleMic(
    icon: ImageVector,
    contentDescription: String,
    isListening: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    controlSize: Dp = 112.dp,
    discSize: Dp = 96.dp,
    iconSize: Dp = 36.dp
) {
    val transition = rememberInfiniteTransition(label = "oracle")
    val breath = rememberBreath(transition, from = 0.25f, to = 0.85f, periodMillis = 1_400)

    // Idle the pulse is gone; live it swells and eases. Animate the change of state
    // itself, so recording ramps in rather than snapping.
    val pulse by animateFloatAsState(
        targetValue = if (isListening) breath else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "pulse"
    )

    val accent = if (isListening) AzureGlow else AzureDeep
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(controlSize)
            .pressScale(interactionSource)
            .selectable(
                selected = isListening,
                enabled = !isBusy,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // The listening ring: one stroke outside the disc, breathing only while live.
        if (isListening) {
            Canvas(modifier = Modifier.size(controlSize)) {
                drawCircle(
                    color = accent.copy(alpha = 0.35f * pulse),
                    radius = size.minDimension / 2f - 1.dp.toPx(),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        Box(
            modifier = Modifier
                .size(discSize)
                .background(
                    if (isListening) accent.copy(alpha = 0.16f) else GlassFillRaised,
                    CircleShape
                )
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = if (isListening) 0.9f else 0.35f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    color = AzureGlow,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    // Merged into the selectable node above, so the whole control is
                    // announced once, with its selected state.
                    contentDescription = contentDescription,
                    modifier = Modifier.size(iconSize),
                    tint = if (isListening) accent else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

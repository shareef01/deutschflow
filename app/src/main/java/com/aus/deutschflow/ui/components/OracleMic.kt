package com.aus.deutschflow.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aus.deutschflow.ui.theme.AzureDeep
import com.aus.deutschflow.ui.theme.AzureGlow
import com.aus.deutschflow.ui.theme.pressScale
import com.aus.deutschflow.ui.theme.rememberBreath
import com.aus.deutschflow.ui.theme.rememberMeshRotation

/**
 * The recording control, and the one place in the app allowed to be theatrical.
 *
 * Three layers, drawn outward from the icon:
 *
 *  1. A halo, well outside the button, carrying a radial fall-off to transparent. It
 *     is what makes the control look lit from within rather than filled in.
 *  2. A sweep-gradient mesh on the disc itself, rotated by a single continuous
 *     transition. A sweep rather than a linear gradient because a linear one rotated
 *     through 360 degrees visibly sweeps back and forth; a sweep just turns.
 *  3. The icon.
 *
 * Idle, the halo breathes and the mesh sits at the deep end of the ramp. Live, both
 * jump to full azure and the halo stops breathing and simply burns - the state change
 * has to be readable at a glance from across a room, which a subtler difference is
 * not.
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
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "oracle")
    val rotation = rememberMeshRotation(transition)
    val breath = rememberBreath(transition)

    // Idle keeps breathing; live burns steady. animateFloatAsState rather than reading
    // the breath directly so the change of state is itself animated - the glow ramps
    // up rather than snapping.
    val glowStrength by animateFloatAsState(
        targetValue = if (isListening) 1f else breath,
        animationSpec = tween(durationMillis = 400),
        label = "glowStrength"
    )

    val accent = if (isListening) AzureGlow else AzureDeep
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(HALO)
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
        // 1. The halo. Drawn at full size and faded by the glow strength, so the lit
        // area grows and shrinks rather than the whole control changing opacity.
        Canvas(modifier = Modifier.size(HALO)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.45f * glowStrength),
                        accent.copy(alpha = 0.12f * glowStrength),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension / 2f
                ),
                radius = size.minDimension / 2f
            )
        }

        // 2. The mesh. One sweep gradient turning continuously, with the bright stop
        // repeated at both ends so there is no seam where the sweep wraps.
        Box(
            modifier = Modifier
                .size(DISC)
                .graphicsLayer { rotationZ = rotation }
                .mesh(accent, glowStrength)
        )

        // 3. The icon, upright - outside the rotating layer, or it would turn with it.
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = AzureGlow,
                strokeWidth = 3.dp
            )
        } else {
            Icon(
                imageVector = icon,
                // Merged into the selectable node above, so the whole control is
                // announced once, with its selected state.
                contentDescription = contentDescription,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** The rotating body of the disc, kept out of the layout above for legibility. */
private fun Modifier.mesh(accent: Color, strength: Float): Modifier =
    this.drawBehind {
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    accent,
                    AzureDeep.copy(alpha = 0.55f),
                    Color(0xFF061021),
                    AzureDeep.copy(alpha = 0.55f),
                    accent
                ),
                center = Offset(size.width / 2f, size.height / 2f)
            )
        )
        // A bright rim, strongest while listening, so the disc has an edge against the
        // halo instead of dissolving into it.
        drawCircle(
            color = accent.copy(alpha = 0.5f + 0.5f * strength),
            radius = size.minDimension / 2f,
            style = Stroke(width = 2.dp.toPx())
        )
    }

private val HALO = 200.dp
private val DISC = 108.dp

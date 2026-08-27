package com.aus.deutschflow.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The surface treatment every card and input shares, in one place.
 *
 * A quiet, premium surface: an opaque fill one step above the ground, a hairline
 * edge at low alpha, and a whisper of elevation. Separation comes from the value
 * step, not from a glowing border — the old treatment (translucent fill plus an
 * azure gradient edge) read as glass lit by a lamp, and it competed with the
 * content for attention on every screen.
 */
val GlassShape = RoundedCornerShape(16.dp)

/**
 * The single edge treatment every surface and input shares.
 *
 * A solid hairline in the accent colour at low alpha. Solid rather than gradient:
 * a gradient edge draws the eye to the lit corner; a hairline just separates the
 * card from the ground. [alpha] lets focused inputs and recording controls turn
 * the edge up without a second treatment.
 */
/**
 * The cyan edge on a glass surface.
 *
 * At 0.16 this composited to 1.38:1 against the card it outlined - the same
 * invisibility the neutral Outline had, and for the same reason: the card is only
 * 1.10:1 above the ground, so the edge is doing all the work of saying where it
 * ends, and it was not doing any. 0.5 brings it to 3.21:1, the bar WCAG sets for a
 * boundary that identifies a component. It stays cyan: the colour is the brand, the
 * alpha was the bug.
 */
fun glassBorderBrush(glow: Color = AzureGlow, alpha: Float = 0.5f): Brush =
    SolidColor(glow.copy(alpha = alpha))

/**
 * @param shape must match whatever the caller clips its content to, or the fill and
 * the edge will disagree at the corners.
 */
fun Modifier.glassSurface(
    shape: Shape = GlassShape,
    fill: Color = GlassFill,
    glow: Color = AzureGlow
): Modifier = this
    // Shadow outermost so it is not clipped away by the clip below it.
    .shadow(
        elevation = 2.dp,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.30f),
        spotColor = Color.Black.copy(alpha = 0.30f)
    )
    .clip(shape)
    .background(fill, shape)
    .border(
        width = 1.dp,
        brush = glassBorderBrush(glow),
        shape = shape
    )

/**
 * Presses the surface into the screen and lets it spring back.
 *
 * Takes the [InteractionSource] the component already owns rather than installing a
 * second clickable, so a Button keeps its own semantics, ripple and disabled state
 * and this only reads the press. A spring rather than a tween: the release is the
 * part that has to feel physical, and a linear return reads as a slide.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** For surfaces that have no interaction source of their own to borrow. */
@Composable
fun rememberPressSource(): MutableInteractionSource =
    remember { MutableInteractionSource() }

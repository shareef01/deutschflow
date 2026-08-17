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
fun glassBorderBrush(glow: Color = AzureGlow, alpha: Float = 0.16f): Brush =
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

/**
 * The angle driving every rotating gradient in the app, in degrees.
 *
 * One slow revolution, shared by anything that wants to turn, so the motion reads
 * as ambient rather than as a progress indicator.
 */
@Composable
fun rememberMeshRotation(
    transition: androidx.compose.animation.core.InfiniteTransition,
    periodMillis: Int = 12_000
): Float {
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "meshRotation"
    )
    return angle
}

/**
 * The idle breath: a slow alpha swell, so a control that is doing nothing still looks
 * alive without asking for attention.
 */
@Composable
fun rememberBreath(
    transition: androidx.compose.animation.core.InfiniteTransition,
    from: Float = 0.35f,
    to: Float = 0.75f,
    periodMillis: Int = 2_600
): Float {
    val breath by transition.animateFloat(
        initialValue = from,
        targetValue = to,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    return breath
}

/** The accent ramp, for text and icons that should carry the brand rather than sit in it. */
fun azureBrush(): Brush = Brush.linearGradient(colors = listOf(AzureGlow, AzureDeep))

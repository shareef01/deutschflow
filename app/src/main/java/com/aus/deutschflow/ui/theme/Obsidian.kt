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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The Obsidian surface treatment, in one place.
 *
 * Every card in the app is this: a translucent white fill over the void, and a
 * hairline edge that carries the azure at one corner and fades to nothing at the
 * other. Both halves matter. The fill alone is too faint to find; the border alone
 * outlines an empty hole.
 *
 * A gradient border rather than a solid one because a solid azure edge at this
 * weight rings the card like a selection state. The gradient runs from the
 * top-left corner, where the light is, fading away by the far corner - a lit
 * corner rather than a lit edge, which is what makes a flat panel look like a
 * pane of glass catching one lamp.
 */
val GlassShape = RoundedCornerShape(24.dp)

/**
 * The single edge treatment every glass surface and glass input shares.
 *
 * Cards used to paint this inline, and text fields did not paint it at all - each
 * drew its own plain outline. Extracting it means a card and the search bar next to
 * it are one stroke: the same azure corner fading to nothing, at the same weight.
 * A gradient rather than a solid so the edge reads as a lit pane, not a selection.
 */
fun glassBorderBrush(glow: Color = AzureGlow): Brush = Brush.linearGradient(
    colors = listOf(
        glow.copy(alpha = 0.35f),
        glow.copy(alpha = 0.12f),
        Color.Transparent
    ),
    start = Offset.Zero,
    end = Offset.Infinite
)

/**
 * @param shape must match whatever the caller clips its content to, or the fill and
 * the edge will disagree at the corners.
 */
fun Modifier.glassSurface(
    shape: Shape = GlassShape,
    fill: Color = GlassFill,
    glow: Color = AzureGlow
): Modifier = this
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
 * One slow revolution. Deliberately not tied to a component: the microphone's mesh
 * and anything else that wants to turn should turn together, and at a speed that
 * reads as ambient rather than as a progress indicator.
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

/** The accent ramp, for text and icons that should carry the azure rather than sit in it. */
fun azureBrush(): Brush = Brush.linearGradient(colors = listOf(AzureGlow, AzureDeep))

package com.aus.deutschflow.ui.components

import com.aus.deutschflow.ui.theme.AppTheme
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin

/**
 * A live input-level meter, drawn entirely in the draw phase.
 *
 * It takes the amplitude as a [State] and reads it *inside* the Canvas draw lambda,
 * never in composition. The recogniser emits a new RMS value many times a second;
 * reading it this way invalidates only the draw pass, so a 30fps waveform costs zero
 * recompositions. Each bar's height is a fixed envelope scaled by the instantaneous
 * level, which is enough to read as a voice without a per-bar ring buffer.
 */
@Composable
fun AudioWaveform(
    amplitude: State<Float>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    bars: Int = 28,
    color: Color = AppTheme.colors.azureGlow
) {
    Canvas(modifier = modifier) {
        // Idle, the meter collapses to a faint static comb so the card reads as
        // reserved rather than empty.
        val level = if (isActive) amplitude.value.coerceIn(0f, 1f) else 0f
        if (bars <= 1) return@Canvas

        val slot = size.width / (bars * 2f - 1f)
        val barWidth = slot
        val corner = CornerRadius(barWidth / 2f)

        for (i in 0 until bars) {
            val envelope = 0.30f + 0.70f * sin(PI * i / (bars - 1)).toFloat()
            val height = size.height * (0.06f + 0.94f * envelope * level)
            val x = i * slot * 2f
            val y = (size.height - height) / 2f

            drawRoundRect(
                color = color.copy(alpha = 0.30f + 0.70f * level),
                topLeft = Offset(x, y),
                size = Size(barWidth, height),
                cornerRadius = corner
            )
        }
    }
}

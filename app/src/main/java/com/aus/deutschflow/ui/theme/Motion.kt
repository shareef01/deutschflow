package com.aus.deutschflow.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * How long things take, and how they get there.
 *
 * The app already animated in a dozen places, each with its own number - 300, 400,
 * 500, 1000 and 1200 all appeared, sometimes for the same job on different screens.
 * A short scale means a transition reads as the same product wherever it happens.
 */
object Motion {

    /** A control acknowledging a touch. Below this it is not perceived as motion. */
    const val QUICK = 150

    /** The default: one thing replacing another, a banner arriving. */
    const val STANDARD = 300

    /** Movement the eye is meant to follow, like the study card turning over. */
    const val DELIBERATE = 500

    /** One breath of the recording pulse. Slow enough to read as calm, not urgent. */
    const val PULSE_PERIOD = 1200

    /** Enters and exits. Material's standard curve, named so it stops being retyped. */
    val Standard: Easing = FastOutSlowInEasing
}

/**
 * Whether the person using the app has asked for less movement.
 *
 * Android expresses this as an animation duration scale of zero - set by "Remove
 * animations" in accessibility settings, or by turning animator duration off in
 * developer options. Honouring it is not decoration: for some people motion on a
 * screen is a trigger for nausea or migraine, and a looping pulse is the worst
 * shape of it.
 *
 * Provided through a CompositionLocal so a composable can ask without reaching for a
 * Context, and so tests and previews can force either answer.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Reads the system's current animation scale. */
@Composable
@ReadOnlyComposable
fun systemPrefersReducedMotion(): Boolean {
    // Previews and inspection have no real ContentResolver worth consulting.
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    return Settings.Global.getFloat(
        resolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
}

/**
 * [duration] normally, and nothing at all when movement is unwelcome.
 *
 * Zero rather than a skipped animation, so the end state still arrives - the thing
 * being animated is the point, the travel is not.
 */
@Composable
@ReadOnlyComposable
fun motionDuration(duration: Int): Int = if (LocalReducedMotion.current) 0 else duration

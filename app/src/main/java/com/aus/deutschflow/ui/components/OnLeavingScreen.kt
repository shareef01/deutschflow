package com.aus.deutschflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Runs [action] when the screen is backgrounded, and again when it leaves
 * composition.
 *
 * The recording screens need both halves. Their ViewModels are scoped to the
 * navigation back stack entry, which is *saved* rather than destroyed when the user
 * switches tabs, so nothing else ever tells the recognizer to let go of the
 * microphone - it stayed live behind whatever screen the user moved on to.
 *
 * [action] must be safe to run more than once: leaving the app entirely fires
 * ON_STOP and then, eventually, disposal.
 */
@Composable
fun OnLeavingScreen(action: () -> Unit) {
    val currentAction by rememberUpdatedState(action)
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentAction()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentAction()
        }
    }
}

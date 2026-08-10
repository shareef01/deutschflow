package com.aus.deutschflow.ui.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WidgetUpdater"

/**
 * Redraws the home screen widget after the library changes.
 *
 * The widget declares `updatePeriodMillis` of a day and reads the vocabulary table
 * once per redraw, so without this a word saved at noon did not reach the home screen
 * until the system's next tick. That was easy to miss when the only way to add a word
 * was a successful AI call; now that words can be typed in by hand, it is the obvious
 * next thing a user looks at.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun refresh() {
        try {
            WordWidget().updateAll(context)
        } catch (e: Exception) {
            // Never let a home screen redraw take down the write that triggered it.
            Log.w(TAG, "Could not refresh the widget", e)
        }
    }
}

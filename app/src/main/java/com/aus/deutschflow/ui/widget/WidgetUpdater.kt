package com.aus.deutschflow.ui.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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

    // Glance tolerates a fresh instance per redraw, but this class is a @Singleton,
    // so holding one costs nothing and says the widget is a thing, not a value.
    private val widget = WordWidget()

    suspend fun refresh() {
        try {
            widget.updateAll(context)
        } catch (e: CancellationException) {
            // Not a widget failure. The callers are viewModelScope launches, so this
            // is the screen going away mid-redraw; swallowing it would log a warning
            // for something that did not go wrong and let the coroutine run on past
            // its own cancellation. Same rule as GroqHelper.translateAndExtract.
            throw e
        } catch (e: Exception) {
            // Never let a home screen redraw take down the write that triggered it.
            Log.w(TAG, "Could not refresh the widget", e)
        }
    }
}

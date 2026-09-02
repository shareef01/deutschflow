package com.aus.deutschflow.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * `viewModelScope.launch`, minus the crash.
 *
 * `viewModelScope` uses a SupervisorJob, which stops a failing child cancelling its
 * siblings - and does nothing else. It installs no exception handler, so an uncaught
 * throwable in a child still reaches the thread's default handler and terminates the
 * process. Every write in this app went out through a bare `launch`, which meant a
 * disk-full or corrupted-database error while saving a word took the whole app down,
 * in a project with no crash reporting to notice it had happened.
 *
 * [CancellationException] is rethrown rather than caught. Swallowing it would report
 * an ordinary navigation as a failure and delay the scope's own shutdown - the same
 * rule GroqHelper and WidgetUpdater already apply.
 *
 * @param onError runs on the main thread when the block fails, for the screens that
 * have somewhere to put the news. Omit it for housekeeping the user cannot act on;
 * the log still gets the reason.
 */
fun ViewModel.launchGuarded(
    tag: String,
    onError: (Exception) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit
): Job = viewModelScope.launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "Background work failed", e)
        onError(e)
    }
}

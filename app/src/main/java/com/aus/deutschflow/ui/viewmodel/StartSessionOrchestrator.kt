package com.aus.deutschflow.ui.viewmodel

import com.aus.deutschflow.util.StartSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordination boundary for a single, microphone-started session that must wait
 * for `preferenceManager.selectedDialect.first()` before opening the mic.
 *
 * The delay under test is that dialect read — held distinct from the
 * SpeechRecognizerHelper model-download path, which lives in the recognizer.
 *
 * Ownership is attempt-scoped so a cancelled or failed earlier attempt can
 * never re-arm the gate out from under a later one:
 * - [start] increments a monotonic token on every accepted attempt.
 * - A previous attempt releases the gate only if its token is still current.
 * - CancellationException is preserved (screen back-press) and does not release
 *   the gate — [cancel] owns that, and only for the attempt it still holds.
 *
 * The recording entry point (SpeechRecognizerHelper.startListening) is not
 * touched; this boundary sits strictly upstream of it, which is what makes the
 * dialect-read ordering and the cancellation contract observable in a JVM test.
 */
internal class StartSessionOrchestrator(
    private val dialect: Flow<String>,
    private val gate: StartSessionGate = StartSessionGate(),
) {

    /** Monotonic token; a stale attempt fails the `attempt == token` ownership test. */
    private var attempt = 0L

    private var job: Job? = null

    /** true while a start is pending (dialect read in flight, mic not yet opened). */
    val isPending: Boolean
        get() = synchronized(this) { job?.isActive == true }

    /**
     * Begins an attempt on [scope]: reads the dialect, then invokes [onStarted]
     * to open the mic.
     *
     * - CancellationException propagates intact: a screen that leaves composition
     *   cancels this Job and the cancellation must not look like a failure.
     * - Any other exception is delivered to [onError] (the VM localizes it) and
     *   this attempt's gate ownership is released, so the next start succeeds.
     *
     * [onAccepted] runs synchronously once the gate is acquired, before any
     * coroutine work, with the new attempt token.
     *
     * @return the attempt token if the gate was acquired, or null if a previous
     *   attempt is still pending.
     */
    fun start(
        scope: CoroutineScope,
        onStarted: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onAccepted: (Long) -> Unit = {},
    ): Long? {
        val myAttempt = synchronized(this) {
            if (!gate.tryStart()) return null
            ++attempt
        }
        // Runs before the coroutine can start, so per-attempt state set here (attempt id,
        // cleared errors) can never overwrite what onStarted/onError produce.
        onAccepted(myAttempt)
        // Publish the Job before starting the coroutine. stillOwns() reads the
        // `job` field, so under eager (e.g. Dispatchers.Unconfined) execution the
        // body must not run before the assignment — otherwise it observes a null
        // job, skips onStarted, and leaves the gate held. A lazy Job is published
        // first, then start() dispatches it.
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // The delay under test: the saved dialect, read as a cold flow so a
                // corrupt/IO failure surfaces here rather than mid-recognition.
                val resolvedDialect = dialect.first()
                // A cancel that raced the read must not open the mic for a dead attempt.
                if (!isActive || !stillOwns(myAttempt)) return@launch
                onStarted(resolvedDialect)
            } catch (e: CancellationException) {
                // Cooperative cancel: not a failure. The gate is re-armed by
                // cancel() for the attempt that still owns it.
                throw e
            } catch (e: Exception) {
                // Preference read / start failed: surface it and release this
                // attempt's ownership so the next start succeeds.
                onError(e)
                releaseIfOwner(myAttempt)
            }
        }
        synchronized(this) { job = newJob }
        newJob.start()
        return myAttempt
    }

    /**
     * Cancels the in-flight attempt. The gate is re-armed only if this call
     * still owns it, so a start that re-armed the gate while we were cancelling
     * is not released by a stale attempt.
     */
    fun cancel() {
        val owner = synchronized(this) { attempt }
        synchronized(this) { job?.cancel() }
        synchronized(this) {
            if (attempt == owner) {
                job = null
                gate.release()
            }
        }
    }

    /**
     * Re-arms the gate for the attempt identified by [attempt] — the recognizer
     * terminal event belonging to that attempt's session. A stale event for a
     * superseded attempt is a no-op: it cannot re-arm a gate that a newer attempt
     * now holds, so a late callback cannot drop a newer pending lookup.
     */
    fun release(attempt: Long) {
        synchronized(this) {
            if (attempt == this.attempt) gate.release()
        }
    }

    /** Tears the job down on ViewModel teardown; does NOT re-arm the gate. */
    fun clear() {
        synchronized(this) { job?.cancel(); job = null }
    }

    private fun stillOwns(myAttempt: Long): Boolean =
        synchronized(this) { attempt == myAttempt && job?.isActive == true }

    private fun releaseIfOwner(myAttempt: Long) {
        synchronized(this) {
            if (attempt == myAttempt) gate.release()
        }
    }
}

package com.aus.deutschflow.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreDrainTest {

    /**
     * A child whose cancellation cleanup blocks on [barrier], under NonCancellable so
     * cancellation cannot tear it down mid-cleanup. [started] proves the child reached
     * its try-block; [cleanupEntered] proves it entered finally and is now blocked on
     * [barrier]. Both are explicit handshakes, so the test never waits on a wall-clock
     * guess (the old delay(200) race that made drain.isCompleted non-deterministic).
     */
    private suspend fun CoroutineScope.blockedChild(
        started: CompletableDeferred<Unit>,
        cleanupEntered: CompletableDeferred<Unit>,
        barrier: CompletableDeferred<Unit>,
    ) {
        launch {
            started.complete(Unit)
            try {
                delay(60_000)
            } finally {
                cleanupEntered.complete(Unit)
                // Held until the test releases it: the drain's join() must outlive the
                // cancel request, so cancellation must not cancel this finally block.
                withContext(NonCancellable) { barrier.await() }
            }
        }
    }

    @Test
    fun cancelAndDrainBlocksUntilChildrenFinish() = runBlocking(Dispatchers.IO) {
        val started = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val barrier = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.blockedChild(started, cleanupEntered, barrier)

        // started guarantees the child is running before we cancel; cleanupEntered
        // guarantees it is in finally (blocked on barrier) before we assert. The 5s
        // budget is headroom, not a scheduling guess - the child is NonCancellable-blocked,
        // so the drain cannot possibly complete within it.
        started.await()
        val drain = async(Dispatchers.IO) { cancelAndDrain(scope, 5_000) }
        cleanupEntered.await()
        assertFalse(
            "cancelAndDrain must not return while a child is still cleaning up",
            drain.isCompleted,
        )

        barrier.complete(Unit)
        assertTrue("cancelAndDrain must succeed once cleanup can finish", drain.await())
    }

    @Test
    fun timedOutDrainReturnsFalse() = runBlocking(Dispatchers.IO) {
        val started = CompletableDeferred<Unit>()
        val barrier = CompletableDeferred<Unit>() // held: cleanup never finishes
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.blockedChild(started, CompletableDeferred(), barrier)

        started.await()
        // Direct suspending call: cancel, then the child's NonCancellable cleanup holds
        // the join so withTimeoutOrNull must time out and return false. No delay/isCompleted
        // guesswork - the timeout is the thing under test.
        val drained = cancelAndDrain(scope, 300)
        assertFalse("cancelAndDrain must report failure when cleanup is still in flight", drained)

        // Release the held child so its scope and this runBlocking exit cleanly.
        barrier.complete(Unit)
        scope.coroutineContext[Job]?.join()
        Unit
    }

    // No fabricated DataStore file is asserted here: file deletion is
    // TestPreferencesRule.after()'s contract, and that rule (including the timeout /
    // leak-rejection behaviour) is exercised under instrumentation in
    // TestPreferencesRuleLeakTest. cancelAndDrain owns the drain contract; the file is
    // owned by the rule - keeping the two apart is what the teardown-coverage gap asked for.
}

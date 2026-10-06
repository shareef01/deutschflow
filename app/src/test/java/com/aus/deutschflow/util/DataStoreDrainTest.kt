package com.aus.deutschflow.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataStoreDrainTest {

    /**
     * A child coroutine whose cancellation cleanup is gated behind a controlled barrier.
     * This is the only shape that deterministically proves cancelAndDrain() waits for
     * cleanup to finish instead of returning the moment cancellation is *requested*
     * (the old isActive-loop failure mode) - and lets the timeout path be exercised.
     */
    private suspend fun CoroutineScope.barrierChild(barrier: CompletableDeferred<Unit>) {
        launch {
            try {
                delay(60_000)
            } finally {
                // Runs during cancellation; held open until the test releases the barrier.
                withContext(NonCancellable) { barrier.await() }
            }
        }
    }

    @Test
    fun timedOutDrainReturnsFalseAndLeavesTheFile() = runBlocking(Dispatchers.IO) {
        val barrier = CompletableDeferred<Unit>()
        val file = File.createTempFile("drain-timeout", ".pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.barrierChild(barrier)

        // cleanup is still gated by the barrier -> drain must time out and report false
        val drained = cancelAndDrain(scope, 300)
        assertFalse(
            "cancelAndDrain must report failure when cleanup is still in flight",
            drained,
        )
        // The rule's contract: a timeout must NOT delete the file.
        assertTrue("file must survive a timed-out drain", file.exists())

        // release the held cleanup, then drain succeeds and the file can be removed
        barrier.complete(Unit)
        val drainedAgain = cancelAndDrain(scope, 5_000)
        assertTrue("cancelAndDrain must succeed once cleanup can finish", drainedAgain)
        file.delete()
        assertFalse(file.exists())
    }

    @Test
    fun drainDoesNotReturnUntilCleanupFinishes() = runBlocking(Dispatchers.IO) {
        val barrier = CompletableDeferred<Unit>()
        var cleanupRan = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val child = scope.launch {
            try {
                delay(60_000)
            } finally {
                withContext(NonCancellable) { barrier.await() }
                cleanupRan = true
            }
        }

        val drainResult = async { cancelAndDrain(scope, 5_000) }
        // give the drain coroutine time to reach job.join(), where it now blocks
        delay(200)
        assertFalse("drain must not return while cleanup is gated", drainResult.isCompleted)
        assertFalse("cleanup must not have run while the barrier is closed", cleanupRan)

        barrier.complete(Unit)
        val drained = drainResult.await()
        assertTrue(drained)
        assertTrue(cleanupRan)
        assertTrue(child.isCompleted)
    }
}

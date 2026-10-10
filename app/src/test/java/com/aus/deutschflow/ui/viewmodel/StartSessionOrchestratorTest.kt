package com.aus.deutschflow.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests the production start ordering against the StartSessionOrchestrator seam.
 *
 * The delay under test is `selectedDialect.first()` — awaited before the
 * recording entry point. It is deliberately distinct from the
 * SpeechRecognizerHelper model-download path, which is recognizer plumbing and
 * is not exercised here.
 */
class StartSessionOrchestratorTest {

    private val timeoutMs: Long = 5_000

    /** A dialect flow that suspends until a value is sent on the channel. */
    private fun controlledDialect(): Pair<Channel<String>, Flow<String>> {
        val channel = Channel<String>(Channel.BUFFERED)
        return channel to flow {
            for (value in channel) {
                emit(value)
            }
        }
    }

    /**
     * A cold dialect flow that throws on the first collection (simulating a
     * corrupt/IO preference read) and emits on the next, mirroring how
     * selectedDialect.first() re-reads the store per attempt.
     */
    private fun throwingThenEmitting(): Flow<String> {
        var failed = false
        return flow {
            if (!failed) {
                failed = true
                throw IOException("corrupt DataStore")
            }
            emit("de-DE")
        }
    }

    @Test
    fun start_waits_for_dialect_then_calls_onStarted_once() = runBlocking {
        withTimeout(timeoutMs) {
            var starts = 0
            var seen = ""
            val (channel, dialect) = controlledDialect()
            val orch = StartSessionOrchestrator(dialect)

            val token = orch.start(this, onStarted = { starts++; seen = it }, onError = { })
            assertNotNull(token)
            assertTrue(orch.isPending)

            // The dialect resolves only after the start was requested.
            channel.send("de-DE")
            yield()

            assertEquals("onStarted fires once the dialect resolves", 1, starts)
            assertEquals("de-DE", seen)
            assertFalse(orch.isPending)
        }
    }

    @Test
    fun cancel_before_dialect_emits_zero_starts() = runBlocking {
        withTimeout(timeoutMs) {
            var starts = 0
            val (channel, dialect) = controlledDialect()
            val orch = StartSessionOrchestrator(dialect)

            orch.start(this, onStarted = { starts++ }, onError = { })
            assertTrue(orch.isPending)
            orch.cancel()

            // The dialect resolves only after the cancel.
            channel.send("de-DE")
            yield()

            assertEquals("onStarted must not fire after a cancel", 0, starts)
            assertFalse(orch.isPending)
        }
    }

    @Test
    fun preferenceReadFailure_releasesGate_nextAttemptSucceeds() = runBlocking {
        withTimeout(timeoutMs) {
            var starts = 0
            var errors = 0
            val orch = StartSessionOrchestrator(throwingThenEmitting())

            val token = orch.start(this, onStarted = { starts++ }, onError = { errors++ })
            assertNotNull(token)

            // first() throws -> onError is invoked and this attempt's gate is released.
            yield()
            assertEquals("failure surfaces via onError", 1, errors)
            assertEquals("no start through a failing read", 0, starts)

            // The same flow now emits, so the gate must be re-armed for the next attempt.
            val token2 = orch.start(this, onStarted = { starts++ }, onError = { errors++ })
            assertNotNull("next attempt must acquire the gate", token2)

            yield()
            assertEquals("second attempt starts once", 1, starts)
            assertEquals("still only one error", 1, errors)
        }
    }

    @Test
    fun twoStartsWhileLookupPending_oneStartRequest() = runBlocking {
        withTimeout(timeoutMs) {
            var starts = 0
            val (channel, dialect) = controlledDialect()
            val orch = StartSessionOrchestrator(dialect)

            val a = orch.start(this, onStarted = { starts++ }, onError = { })
            assertNotNull(a)

            // Dialect still pending -> the second start is rejected, not queued.
            val b = orch.start(this, onStarted = { starts++ }, onError = { })
            assertNull("a second start while one is pending is rejected", b)

            channel.send("de-DE")
            yield()

            assertEquals("exactly one start request", 1, starts)
            assertFalse(orch.isPending)
        }
    }

    @Test
    fun cancelledAttempt_doesNotReleaseNewerAttemptGate() = runBlocking {
        withTimeout(timeoutMs) {
            var starts = 0
            val (channel, dialect) = controlledDialect()
            val orch = StartSessionOrchestrator(dialect)
            try {
                val a = orch.start(this, onStarted = { starts++ }, onError = { })
                assertNotNull(a)
                assertTrue("A is pending", orch.isPending)

                orch.cancel() // A cancelled; its ownership is released
                // A must enter its controlled cancellation cleanup before B begins.
                yield()
                assertFalse("cancelled attempt is no longer pending", orch.isPending)

                val b = orch.start(this, onStarted = { starts++ }, onError = { })
                assertNotNull("B re-acquires the gate", b)
                assertTrue("B is pending", orch.isPending)

                // A stale start while B is pending must be rejected, not queued behind it.
                val c = orch.start(this, onStarted = { starts++ }, onError = { })
                assertNull("a start while B is pending is rejected", c)

                // Only B is consuming this channel; A was already cancelled.
                channel.send("de-DE")
                yield()

                assertEquals("only B starts; A's cleanup did not drop B's gate", 1, starts)
                orch.clear()
                assertFalse(orch.isPending)
            } finally {
                // Reap the live job even if an assertion above threw, so a failed
                // run cannot leak a coroutine into the next test.
                orch.clear()
            }
        }
    }

    @Test
    fun release_rearmsGate_forNextStart() = runBlocking {
        withTimeout(timeoutMs) {
            val (channel, dialect) = controlledDialect()
            val orch = StartSessionOrchestrator(dialect)

            // First attempt acquires the gate and resolves cleanly.
            val token1 = orch.start(this, onStarted = { }, onError = { })
            assertNotNull(token1)
            channel.send("de-DE")
            yield() // first attempt completes via onStarted

            // The recognizer committing re-arms the gate for the next attempt.
            orch.release(token1!!)

            val token2 = orch.start(this, onStarted = { }, onError = { })
            assertNotNull("release() must re-arm the gate", token2)

            channel.send("de-DE")
            yield() // second attempt completes via onStarted
            orch.clear()
        }
    }

    @Test
    fun clear_doesNotReleaseGate() = runBlocking {
        withTimeout(timeoutMs) {
            val (channel, dialect) = controlledDialect()
            val orch = StartSessionOrchestrator(dialect)

            orch.start(this, onStarted = { }, onError = { })
            orch.clear() // onCleared path: tears down the job, does not re-arm the gate

            // The gate is still held: a new start is rejected until the recognizer
            // commits or cancels it.
            assertNull("clear must not re-arm the gate", orch.start(this, onStarted = { }, onError = { }))
        }
    }

    @Test
    fun immediateDialect_underEagerExecution_invokesOnStarted() = runBlocking {
        withTimeout(timeoutMs) {
            // Eager execution (Unconfined) + a dialect that emits with no suspension
            // point: the coroutine body runs during start() unless the job is
            // published before it starts. onStarted must still fire.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                var started = false
                val dialect = flow { emit("de-DE") }
                val orch = StartSessionOrchestrator(dialect)
                val token = orch.start(scope, onStarted = { started = true }, onError = { })
                assertNotNull(token)
                assertTrue(
                    "onStarted must fire even when the dialect emits immediately under eager execution",
                    started
                )
                orch.clear()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun staleReleaseAttempt_doesNotDropNewerPendingGate() = runBlocking {
        withTimeout(timeoutMs) {
            val (channel, dialect) = controlledDialect()
            val orch = StartSessionOrchestrator(dialect)
            try {
                val a = orch.start(this, onStarted = { }, onError = { })
                assertNotNull(a)
                // A's recognizer commits A's session — re-arms the gate.
                orch.release(a!!)

                // B acquires the gate and waits on its dialect.
                val b = orch.start(this, onStarted = { }, onError = { })
                assertNotNull(b)
                assertTrue("B is pending", orch.isPending)

                // A stale terminal event for the superseded attempt A must not re-arm
                // a gate that B now holds.
                orch.release(a)
                val c = orch.start(this, onStarted = { }, onError = { })
                assertNull("a stale attempt's release cannot drop a newer pending gate", c)

                channel.send("de-DE")
                yield()
                orch.clear()
            } finally {
                orch.clear()
            }
        }
    }

    @Test
    fun cancelledBeforeExecution_rearmsForRetry() = runBlocking {
        withTimeout(timeoutMs) {
            var started = 0
            val (channel, dialect) = controlledDialect()
            val orch = StartSessionOrchestrator(dialect)
            try {
                val a = orch.start(this, onStarted = { started++ }, onError = { })
                assertNotNull(a)
                assertTrue(orch.isPending)
                // Cancel before the (lazy) job's body runs.
                orch.cancel()
                // A's cancelled job finishes its cleanup before B begins.
                yield()
                assertFalse("cancelled before execution: no start", orch.isPending)
                assertEquals(0, started)
                // The gate is re-armed; a retry acquires it.
                val b = orch.start(this, onStarted = { started++ }, onError = { })
                assertNotNull("retry acquires the gate", b)
                channel.send("de-DE")
                yield()
                assertEquals("retry starts after a cancelled attempt", 1, started)
                orch.clear()
            } finally {
                orch.clear()
            }
        }
    }

    @Test
    fun onAccepted_runsBeforeOnStarted_andBeforeOnError_underEagerDispatch() = runBlocking {
        withTimeout(timeoutMs) {
            // Unconfined runs the body inline inside start(), the worst case for ordering:
            // anything set after start() returns would land after onStarted/onError.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val events = mutableListOf<String>()
                val ok = StartSessionOrchestrator(flow { emit("de-DE") })
                val token = ok.start(
                    scope,
                    onStarted = { events += "started" },
                    onError = { events += "error" },
                    onAccepted = { events += "accepted:$it" },
                )
                assertEquals(listOf("accepted:$token", "started"), events)

                events.clear()
                val failing = StartSessionOrchestrator(flow<String> { throw IOException("boom") })
                val failToken = failing.start(
                    scope,
                    onStarted = { events += "started" },
                    onError = { events += "error" },
                    onAccepted = { events += "accepted:$it" },
                )
                assertEquals(listOf("accepted:$failToken", "error"), events)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun onAccepted_notCalledForRejectedStart() = runBlocking {
        withTimeout(timeoutMs) {
            val (_, dialect) = controlledDialect()
            val orch = StartSessionOrchestrator(dialect)
            try {
                var accepted = 0
                val a = orch.start(this, onStarted = { }, onError = { }, onAccepted = { accepted++ })
                assertNotNull(a)
                val b = orch.start(this, onStarted = { }, onError = { }, onAccepted = { accepted++ })
                assertNull("duplicate start is rejected", b)
                assertEquals("only the accepted attempt is announced", 1, accepted)
            } finally {
                orch.clear()
            }
        }
    }
}

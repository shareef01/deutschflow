package com.aus.deutschflow.ui.viewmodel

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

            val a = orch.start(this, onStarted = { starts++ }, onError = { })
            assertNotNull(a)
            assertTrue(orch.isPending)

            orch.cancel() // A cancelled; its ownership is released
            assertFalse("cancelled attempt is no longer pending", orch.isPending)

            val b = orch.start(this, onStarted = { starts++ }, onError = { })
            assertNotNull("B re-acquires the gate")
            assertTrue(orch.isPending)

            // Let A's cancelled job finish its cleanup. A must not release B's gate.
            yield()
            yield()

            // Only B is consuming this channel; A was already cancelled.
            channel.send("de-DE")
            yield()

            assertEquals("only B starts; A's cleanup did not drop B's gate", 1, starts)
            assertFalse(orch.isPending)
        }
    }

    @Test
    fun release_rearmsGate_forNextStart() = runBlocking {
        withTimeout(timeoutMs) {
            val (channel, dialect) = controlledDialect()
            val orch = StartSessionOrchestrator(dialect)

            // First attempt acquires the gate and resolves cleanly.
            orch.start(this, onStarted = { }, onError = { })
            channel.send("de-DE")
            yield() // first attempt completes via onStarted

            // The recognizer committing re-arms the gate for the next attempt.
            orch.release()

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
}

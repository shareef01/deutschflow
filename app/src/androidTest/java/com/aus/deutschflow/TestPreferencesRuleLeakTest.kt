package com.aus.deutschflow

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.util.cancelAndDrain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the teardown / file-deletion contract (issues #2 & #3):
 *  - a cleanup timeout surfaces as a test FAILURE (after() throws), and
 *  - the file is NOT deleted when the drain timed out, and
 *  - the next before() refuses the still-owned file instead of reusing it.
 *
 * Uses a short 200ms drain so the test is quick on CI while still deterministically
 * blocking cleanup under NonCancellable (the same barrier shape DataStoreDrainTest uses).
 */
@RunWith(AndroidJUnit4::class)
class TestPreferencesRuleLeakTest {

    @Test
    fun timedOutAfterThrowsLeavesFileAndNextBeforeRefusesReuse() {
        val rule = TestPreferencesRule("leak_regression", drainTimeoutMs = 200)
        rule.before()

        // A child that holds its cleanup open under NonCancellable, so cancelAndDrain's
        // join can never return -> after()'s drain must time out.
        val started = CompletableDeferred<Unit>()
        val barrier = CompletableDeferred<Unit>()
        rule.scope.launch {
            started.complete(Unit)
            try {
                delay(60_000)
            } finally {
                withContext(NonCancellable) { barrier.await() }
            }
        }
        runBlocking(Dispatchers.IO) {
            // child is running, at delay; write a real value so the backing file is
            // materialized on disk (an identity updateData is a no-op and writes nothing).
            started.await()
            rule.dataStore.edit { it[stringPreferencesKey("leak_marker")] = "1" }
        }

        // after() must surface the timeout as a failure (throw) and must NOT delete.
        try {
            rule.after()
            fail("expected after() to throw on cleanup timeout")
        } catch (e: AssertionError) {
            // expected: timeout is a test failure, not a silent log-and-delete
        }
        assertTrue("file must survive a timed-out drain", rule.file.exists())

        // The still-owned file must be refused, not reused, by the next setup.
        try {
            rule.before()
            fail("expected before() to refuse a leaked file")
        } catch (e: AssertionError) {
            // expected: leak detected and rejected
        }

        // Release the held child so its scope exits cleanly, then remove the leftover
        // file so the test is re-runnable on a reused emulator.
        barrier.complete(Unit)
        runBlocking(Dispatchers.IO) { cancelAndDrain(rule.scope, 5_000) }
        rule.file.delete()
    }
}

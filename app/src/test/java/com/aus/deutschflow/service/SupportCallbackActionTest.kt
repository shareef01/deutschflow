package com.aus.deutschflow.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The recognizer's asynchronous capability answer has two distinct rejections, and the
 * guard used to conflate them.
 *
 * `sessionGeneration != currentSession || !_isListening.value` returned by treating a
 * stopped session exactly like a superseded one - destroy the recognizer and walk away.
 * That is correct for a superseded session, whose state a newer one now owns, but wrong
 * for one the user stopped while the check was still in flight: no listener was ever
 * attached, so nothing will lower the "processing" flag stopListening raised, and the
 * screen stays busy until the next start.
 *
 * The decision is pure, so it is asserted here on the JVM without driving the Android
 * helper - the same seam pattern as [SpeechCapabilityDeciderTest]. The regression is the
 * stopped case: against the old guard this expectation is [SupportCallbackAction.DROP]
 * and strands the flag.
 */
class SupportCallbackActionTest {

    @Test
    fun liveSession_proceeds() {
        assertEquals(
            SupportCallbackAction.START,
            supportCallbackAction(sessionGeneration = 1L, callbackSession = 1L, isListening = true)
        )
    }

    @Test
    fun supersededSession_isDropped() {
        assertEquals(
            SupportCallbackAction.DROP,
            supportCallbackAction(sessionGeneration = 2L, callbackSession = 1L, isListening = true)
        )
    }

    /**
     * The regression. A session stopped while the capability check was outstanding must
     * be *aborted* - the callback clears the processing flag - not merely dropped.
     */
    @Test
    fun stoppedSession_isAbortedNotDropped() {
        assertEquals(
            SupportCallbackAction.ABORT,
            supportCallbackAction(sessionGeneration = 1L, callbackSession = 1L, isListening = false)
        )
    }

    /**
     * A newer session that is itself not listening owns the shared state, so this
     * callback must not reset anything on its behalf: superseding wins over stopped.
     */
    @Test
    fun supersededTakesPrecedence_overStopped() {
        assertEquals(
            SupportCallbackAction.DROP,
            supportCallbackAction(sessionGeneration = 2L, callbackSession = 1L, isListening = false)
        )
    }
}

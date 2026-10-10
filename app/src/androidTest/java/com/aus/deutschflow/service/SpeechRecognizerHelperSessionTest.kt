package com.aus.deutschflow.service

import android.content.Context
import android.content.Intent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.awaitCondition
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for stale recogniser callbacks.
 *
 * A recognition session is single-flight at the VM layer, but the platform recogniser
 * delivers callbacks asynchronously on the main looper. When session A is superseded by
 * session B, an in-flight callback belonging to A must not flip a state that B owns —
 * otherwise the VM's ownership-tagged release(currentAttempt) re-arms B's gate on A's
 * behalf, dropping a newer pending lookup. The guard lives in the per-session listener
 * built by [SpeechRecognizerHelper.buildRecognitionListener]; these drive captured
 * listeners against a superseding generation to prove it.
 */
@RunWith(AndroidJUnit4::class)
class SpeechRecognizerHelperSessionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * A seam whose on-device engine is unavailable: startListening() returns early
     * after advancing the session generation, which is all this test needs to drive
     * captured listeners against a superseding session without a real microphone.
     */
    private val unavailableSeam = object : SpeechRecognitionPlatformSeam {
        override fun isOnDeviceRecognitionAvailable(context: Context) = false
        override fun createOnDeviceRecognizer(context: Context): SpeechRecognizer =
            error("unavailable")
        override fun checkRecognitionSupport(
            recognizer: SpeechRecognizer,
            intent: Intent,
            executor: Executor,
            callback: (SupportResult) -> Unit
        ) { }
        override fun triggerModelDownload(recognizer: SpeechRecognizer, intent: Intent) { }
    }

    private fun helper(): SpeechRecognizerHelper {
        val h = SpeechRecognizerHelper(context)
        h.platformSeam = unavailableSeam
        return h
    }

    @Test
    fun staleListenerCallback_doesNotOverwriteLiveSessionState() = runBlocking {
        val h = helper()

        // Session A is started (generation 1), then superseded by session B (generation 2).
        h.startListening("de-DE")
        assertTrue(
            "A's session should advance the generation",
            awaitCondition { h.currentGeneration == 1L }
        )
        val listenerA = h.buildRecognitionListener(1L)

        h.startListening("de-DE")
        assertTrue(
            "B's session should advance the generation",
            awaitCondition { h.currentGeneration == 2L }
        )
        val listenerB = h.buildRecognitionListener(2L)

        // B's live listener opens the mic for the current session.
        listenerB.onReadyForSpeech(null)
        assertTrue("B should have opened the mic", h.isListening.value)

        // A's STALE callback for the superseded session must not close B's mic.
        listenerA.onEndOfSpeech()
        assertEquals(
            "a stale callback cannot overwrite the live session's state",
            true,
            h.isListening.value
        )
    }

    @Test
    fun liveListenerCallback_isNotRejectedAsStale() = runBlocking {
        val h = helper()

        h.startListening("de-DE")
        assertTrue(awaitCondition { h.currentGeneration == 1L })
        val listener = h.buildRecognitionListener(1L)

        // The current session's own callback is not stale: it must still land.
        listener.onReadyForSpeech(null)
        assertEquals("the live session's callbacks must still be accepted", true, h.isListening.value)

        listener.onEndOfSpeech()
        assertEquals(false, h.isListening.value)
    }
}

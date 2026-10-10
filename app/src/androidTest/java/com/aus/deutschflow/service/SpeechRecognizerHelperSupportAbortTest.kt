package com.aus.deutschflow.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aus.deutschflow.awaitCondition
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the stopped-during-support-check window, driven through the real
 * helper on a real main looper.
 *
 * On API 33+ [SpeechRecognizerHelper.startListening] sets `isListening` to true and
 * then waits for the asynchronous `checkRecognitionSupport` answer. If the user stops
 * during that window, `stopListening()` raises `isProcessing` - but no recognizer was
 * ever started, so no terminal callback will ever lower it again. The support answer,
 * when it finally arrives, belongs to a session the user stopped: it must clear the
 * flag, not merely drop the callback as if the session had been superseded.
 *
 * The failure mode is what makes this worth a device: `isProcessing` stranded true
 * leaves the Transcript screen's `isBusy` stuck - mic disabled, busy indicator up,
 * phase pinned to `TRANSCRIBING`. The pure decision is unit-tested by
 * [com.aus.deutschflow.service.SupportCallbackActionTest]; this proves the live helper
 * actually unwinds the flag.
 *
 * The platform seam captures the support callback instead of running it, which is the
 * only way to hold the asynchronous window open deterministically and stop inside it.
 * Only `destroy()` is ever reached on the recognizer - the stopped path never attaches
 * a listener or starts anything - so a normally-created instance is inert here. Skipped
 * below API 33, where the check does not exist and the branch is not taken.
 */
@RunWith(AndroidJUnit4::class)
class SpeechRecognizerHelperSupportAbortTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun stopWhileTheSupportCheckIsOut_clearsProcessing() = runBlocking {
        assumeTrue(
            "the support-check branch is Android 13+ only",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Built on the main thread, as the platform requires. Never driven - the
        // stopped session only ever destroys it - so binding a real service here
        // costs nothing and keeps the platform signature honest.
        val recognizer = arrayOfNulls<SpeechRecognizer>(1)
        instrumentation.runOnMainSync {
            recognizer[0] = SpeechRecognizer.createSpeechRecognizer(context)
        }
        assumeTrue(
            "no speech recognition service on this device; skipping",
            recognizer[0] != null
        )

        val captured = arrayOfNulls<(SupportResult) -> Unit>(1)
        val seam = object : SpeechRecognitionPlatformSeam {
            override fun isOnDeviceRecognitionAvailable(context: Context) = true

            override fun createOnDeviceRecognizer(context: Context): SpeechRecognizer =
                requireNotNull(recognizer[0])

            override fun checkRecognitionSupport(
                recognizer: SpeechRecognizer,
                intent: Intent,
                executor: Executor,
                callback: (SupportResult) -> Unit
            ) {
                // Hold the answer instead of delivering it: models the async window
                // the user can stop inside.
                captured[0] = callback
            }

            override fun triggerModelDownload(recognizer: SpeechRecognizer, intent: Intent) = Unit
        }

        val helper = SpeechRecognizerHelper(context)
        helper.platformSeam = seam

        instrumentation.runOnMainSync { helper.startListening("de-DE") }
        assertTrue(
            "the mic should open before the support answer arrives",
            awaitCondition { helper.isListening.value }
        )
        assertTrue(
            "the support check should have been issued",
            awaitCondition { captured[0] != null }
        )

        // Stop while the capability check is still outstanding.
        instrumentation.runOnMainSync { helper.stopListening() }
        assertTrue(
            "stop should leave the session processing",
            awaitCondition { !helper.isListening.value && helper.isProcessing.value }
        )

        // The answer now lands for a session that was stopped before it could start.
        instrumentation.runOnMainSync { captured[0]?.invoke(SupportResult.Supported(isInstalled = true)) }

        assertTrue(
            "a stopped session must not be left processing forever",
            awaitCondition { !helper.isProcessing.value }
        )
        assertFalse(helper.isProcessing.value)

        instrumentation.runOnMainSync { helper.destroy() }
    }
}

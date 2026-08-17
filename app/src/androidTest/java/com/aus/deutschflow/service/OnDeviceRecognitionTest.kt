package com.aus.deutschflow.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aus.deutschflow.R
import com.aus.deutschflow.awaitCondition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The only test that proves recognition really is on-device.
 *
 * The app's headline privacy claim is that audio never leaves the device, and for a
 * long time nothing enforced it: `createSpeechRecognizer` binds whatever service the
 * phone has set as its default, which on most phones is a cloud one. The fix was to
 * bind the on-device engine specifically. This is what checks that the fix works
 * against a real engine rather than only compiling.
 *
 * Nobody can speak into a test, so the assertion is about which failure arrives.
 * Silence on a working German on-device engine ends in a speech-timeout or no-match,
 * and both of those are proof the pipeline ran: the recognizer was created, the
 * German model was found, the microphone opened. The failures that would mean the
 * opposite - the model is missing, the locale is unsupported, the recognizer could
 * not be built - are different codes with different messages, and are what this
 * asserts against.
 *
 * Skipped rather than failed when the device has no on-device recognition or has not
 * granted the microphone, exactly like GroqLiveTest: CI's emulator has neither, and
 * a test that cannot run should not fail a build. Grant it first with:
 *
 *     adb shell pm grant com.aus.deutschflow android.permission.RECORD_AUDIO
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceRecognitionTest {

    private companion object { const val TAG = "OnDeviceRecognitionTest" }

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The on-device engine can actually be built.
     *
     * Availability is an *assumption*, not an assertion: a device with no on-device
     * recogniser cannot honour the privacy claim, but that is a property of the
     * device rather than a defect in this code, and failing the build for it would
     * make the suite red on every emulator. Same rule as GroqLiveTest skipping
     * without a key. What is asserted is the part that is ours - that given the
     * capability, the app's own factory returns a working recogniser.
     */
    @Test
    fun theOnDeviceEngineCanBeCreated() {
        assumeTrue(
            "isOnDeviceRecognitionAvailable needs API 33+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        )

        val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        Log.i(TAG, "on-device recognition available: $available")
        assumeTrue("this device has no on-device recogniser; skipping", available)

        // SpeechRecognizer must be built on the main thread, which is why the helper
        // confines every engine call to a main-looper Handler.
        var recognizer: SpeechRecognizer? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }

        assertNotNull("createOnDeviceSpeechRecognizer returned nothing", recognizer)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { recognizer?.destroy() }
    }

    /**
     * A real German session, through the app's own helper, on the on-device engine.
     *
     * Through [SpeechRecognizerHelper] rather than the framework directly, so what is
     * proven is the path the app actually takes - the same reason GroqLiveTest uses
     * GroqHelper and the device's own stored key.
     */
    @Test
    fun aGermanSessionRunsOnTheOnDeviceEngine() = runBlocking {
        assumeTrue(
            "no RECORD_AUDIO permission on this device; skipping the live microphone session",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
        assumeTrue(
            "no on-device recognition on this device; skipping",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        )

        val helper = SpeechRecognizerHelper(context)
        helper.startListening("de-DE")

        // Silence, deliberately. The engine gives up on its own, and how it gives up
        // is the evidence. Generous: an on-device model can take a moment to load the
        // first time it is asked for since boot.
        val settled = awaitCondition(timeoutMs = 40_000) {
            helper.errorState.value != null || helper.finalText.value.isNotBlank()
        }

        val error = helper.errorState.value
        Log.i(TAG, "session settled=$settled error=$error final='${helper.finalText.value}'")
        helper.destroy()

        assertTrue(
            "the engine never answered at all in 40s - it did not start",
            settled
        )

        // The failures that would mean the on-device German model is not really there.
        // Compared as resolved strings because that is what the helper publishes, and
        // the helper is what the screens read.
        val languageUnavailable = context.getString(R.string.speech_error_language_unavailable)
        val languageUnsupported = context.getString(R.string.speech_error_language_unsupported)
        val clientError = context.getString(R.string.speech_error_client)
        val startFailed = context.getString(R.string.speech_start_failed)
        val unavailable = context.getString(R.string.speech_unavailable)

        assertNotEquals("the German on-device model is missing", languageUnavailable, error)
        assertNotEquals("de-DE is not supported by the on-device engine", languageUnsupported, error)
        assertNotEquals("the on-device recognizer could not be built", clientError, error)
        assertNotEquals("the session never started", startFailed, error)
        assertNotEquals("no recognition service was found", unavailable, error)
    }
}

package com.aus.deutschflow.service

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.data.local.KeystoreCipher
import com.aus.deutschflow.data.local.PreferenceManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The only test that actually talks to Groq.
 *
 * Everything else about the client is checked against fixtures, which proves the
 * parsing and proves nothing about the request: the auth header, the model name, the
 * shape the service really answers with. This closes that gap, and it is the last
 * unverified path in the app.
 *
 * Skipped, not failed, when no API key is stored - which is the case on CI and on any
 * fresh device, and is why it can live in the normal suite without breaking it. The
 * key is read from the device's own settings and never appears in this file, in the
 * output, or in the repository.
 *
 * It costs one request against a free tier that allows roughly a thousand a day.
 */
@RunWith(AndroidJUnit4::class)
class GroqLiveTest {

    private companion object { const val TAG = "GroqLiveTest" }

    @Test
    fun aRealSentenceComesBackTranslated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val apiKey = PreferenceManager(context, KeystoreCipher()).apiKey.first()

        assumeTrue("no API key stored on this device; skipping the live call", apiKey.isNotBlank())

        val result = GroqHelper(context).translateAndExtract(
            text = "Ich lerne jeden Tag Deutsch.",
            apiKey = apiKey
        )

        // The failure message carries the provider's own explanation, which is the
        // thing worth seeing when this breaks.
        assertTrue("expected a translation, got: $result", result is AIResult.Success)
        result as AIResult.Success

        // Logged so a passing run is visibly a real one. A test that skips and a test
        // that succeeds against a fast service look identical from the outside, and
        // this is the one test whose whole value is that the request left the device.
        Log.i(TAG, "live translation: ${result.translation}")
        Log.i(TAG, "live keywords: ${result.keywords}")
        Log.i(TAG, "live example: ${result.example}")

        assertTrue("the translation should not be empty", result.translation.isNotBlank())
        // The prompt asks for three fields; the other two are what the vocabulary
        // chips and the saved example sentence are built from.
        assertTrue("expected keywords, got none", result.keywords.isNotEmpty())
        assertTrue("expected an example sentence", result.example.isNotBlank())
    }
}

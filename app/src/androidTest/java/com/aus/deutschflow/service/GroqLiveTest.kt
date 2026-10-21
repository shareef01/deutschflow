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
        // Deliberately the app's own store: the key under test is the one the user
        // entered in Settings. Every other test now uses a store of its own, so this
        // one is no longer read after another test has cleared it.
        val apiKey = PreferenceManager(
            PreferenceManager.appDataStore(context),
            KeystoreCipher()
        ).apiKey.first()

        if (apiKey.isBlank()) {
            Log.i(TAG, "no API key stored on this device; skipping the live call")
            return@runBlocking
        }

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

    /**
     * AUDIT_2026-10-19 X-01: gpt-oss reasoning tokens count against
     * max_completion_tokens, so at the default effort an input near the
     * MAX_AI_INPUT_CHARS limit could spend the whole budget thinking and come back
     * "cut off". With reasoning_effort "low" the answer must arrive. Static checks
     * can't prove this - only the real model's token accounting can - so it lives
     * here, under the same skip-without-a-key rule. One more request.
     */
    @Test
    fun anInputAtTheLengthLimitIsNotCutOff() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val apiKey = PreferenceManager(
            PreferenceManager.appDataStore(context),
            KeystoreCipher()
        ).apiKey.first()
        if (apiKey.isBlank()) {
            Log.i(TAG, "no API key stored on this device; skipping the long live call")
            return@runBlocking
        }

        // Varied, ordinary German rather than one sentence repeated, so the model has
        // real grammar to explain - the case that used to exhaust the budget.
        val sentences = listOf(
            "Gestern bin ich mit meiner Schwester in die Stadt gefahren, weil wir neue Schuhe kaufen wollten.",
            "Obwohl es den ganzen Tag geregnet hat, haben wir uns nach dem Einkaufen in ein kleines Café gesetzt.",
            "Der Kellner, der uns bedient hat, kam ursprünglich aus Österreich und sprach einen starken Dialekt.",
            "Wenn ich mehr Zeit hätte, würde ich gern jeden Abend eine Stunde Deutsch lernen und Bücher lesen.",
            "Meine Großeltern haben mir oft erzählt, wie das Leben auf dem Dorf früher ausgesehen hat.",
        )
        val text = buildString {
            var i = 0
            while (length < GroqHelper.MAX_AI_INPUT_CHARS - 120) {
                if (isNotEmpty()) append(' ')
                append(sentences[i % sentences.size])
                i++
            }
        }
        Log.i(TAG, "long live input: ${text.length} chars")

        val result = GroqHelper(context).translateAndExtract(text = text, apiKey = apiKey)

        assertTrue("expected a translation of a ${text.length}-char input, got: $result", result is AIResult.Success)
        result as AIResult.Success
        Log.i(TAG, "long live translation: ${result.translation.length} chars")
        assertTrue("the translation should not be empty", result.translation.isNotBlank())
    }
}

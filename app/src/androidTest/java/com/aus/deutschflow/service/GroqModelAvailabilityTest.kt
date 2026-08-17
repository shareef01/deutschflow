package com.aus.deutschflow.service

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.data.local.KeystoreCipher
import com.aus.deutschflow.data.local.PreferenceManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Can this key actually reach the model the app is configured to use?
 *
 * [GroqLiveTest] proves the request path works, and its failure message is the
 * provider's own sentence - which is how "The model does not exist or you do not
 * have access to it" surfaced. That sentence is two different faults wearing one
 * string: a retired model id, or a key that cannot reach a model that is very much
 * alive. Telling them apart needs the account's own list, and this asks for it.
 *
 * The comment on [GroqHelper.MODEL_NAME] calls model ids "a maintenance item, not
 * a preference", because the app once shipped a Gemini model that was retired
 * underneath it and the only symptom a user saw was "Translation failed". This is
 * the check that turns that into a failing test instead.
 *
 * The key is read from the device's own settings and never appears in this file,
 * in the output, or in the repository - only the model ids it can see do. Skipped
 * rather than failed when no key is stored, exactly like GroqLiveTest.
 */
@RunWith(AndroidJUnit4::class)
class GroqModelAvailabilityTest {

    private companion object {
        const val TAG = "GroqModelAvailability"
        const val MODELS_ENDPOINT = "https://api.groq.com/openai/v1/models"
    }

    @Test
    fun theConfiguredModelIsOneThisKeyCanReach() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val apiKey = PreferenceManager(
            PreferenceManager.appDataStore(context),
            KeystoreCipher()
        ).apiKey.first()

        assumeTrue("no API key stored on this device; skipping", apiKey.isNotBlank())

        val connection = (URL(MODELS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        val status: Int
        val body: String
        try {
            status = connection.responseCode
            body = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
        } finally {
            connection.disconnect()
        }

        // 401 means the key itself is the problem, which is worth saying plainly
        // rather than reporting as "the model is missing".
        assertTrue(
            "the models endpoint answered $status - the stored key may be revoked " +
                "or rejected. Body: ${body.take(300)}",
            status in 200..299
        )

        val ids = JSONObject(body).optJSONArray("data")
            ?.let { array -> (0 until array.length()).map { array.getJSONObject(it).optString("id") } }
            .orEmpty()
            .filter { it.isNotBlank() }
            .sorted()

        // The whole point of the test: what this key can actually see.
        Log.i(TAG, "models reachable with the stored key (${ids.size}): $ids")

        assertTrue("the models endpoint returned no models at all", ids.isNotEmpty())
        assertTrue(
            "the app is configured to use '${GroqHelper.MODEL_NAME}', which this key " +
                "cannot reach. Translation is broken until MODEL_NAME is changed to one " +
                "of: $ids",
            ids.contains(GroqHelper.MODEL_NAME)
        )
    }
}

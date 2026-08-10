package com.aus.deutschflow.service

import android.content.Context
import com.aus.deutschflow.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * Outcome of an AI translation.
 *
 * Failure is a separate case rather than an error string in [Success.translation]:
 * the Save button writes that field straight into the vocabulary table, so a
 * failure message used to be storable as an English translation.
 */
sealed interface AIResult {

    data class Success(
        val translation: String,
        val keywords: List<String>,
        val example: String
    ) : AIResult

    data class Failure(val message: String) : AIResult
}

/**
 * Talks to Groq's OpenAI-compatible chat completions endpoint.
 *
 * Replaces the Gemini client, which was deprecated and archived by Google - its own
 * repository is named `deprecated-generative-ai-android` - and which had already
 * cost the app an outage when the model it named was retired underneath it. Nothing
 * here is Groq-specific except the host and the model name: the request is the
 * OpenAI chat shape that most providers speak, so the next move is a two-line
 * change rather than another SDK migration.
 *
 * Deliberately no HTTP or JSON dependency. HttpURLConnection and org.json are both
 * in the framework, so dropping the Gemini SDK removes its Ktor and
 * kotlinx-serialization payload without adding a replacement.
 *
 * The Context is here for one reason: the messages a user sees are translated, and
 * live in resources. Everything that can be tested without one - the response
 * parsing, the error extraction - is in the companion, so the JVM tests still run
 * without an emulator.
 */
class GroqHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun translateAndExtract(text: String, apiKey: String): AIResult {
        if (apiKey.isBlank()) {
            return AIResult.Failure(context.getString(R.string.ai_no_key))
        }

        return withContext(Dispatchers.IO) {
            try {
                val content = contentOf(post(buildPrompt(text), apiKey))
                parseResponse(content)
                    ?: AIResult.Failure(context.getString(R.string.ai_unreadable))
            } catch (e: Exception) {
                val detail = e.message ?: context.getString(R.string.ai_no_response)
                AIResult.Failure(context.getString(R.string.ai_failed, detail))
            }
        }
    }

    private fun post(prompt: String, apiKey: String): String {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }

        return try {
            connection.outputStream.use { it.write(requestBody(prompt).toByteArray()) }

            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                // The body carries the reason - an expired key, a retired model, a
                // rate limit - and all of them are worth putting in front of the user.
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException(errorMessage(connection.responseCode, body))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBody(prompt: String): String = JSONObject().apply {
        put("model", MODEL_NAME)
        // Low, not zero: this is a translation, not a creative writing task, but the
        // example sentence still wants some room.
        put("temperature", 0.2)
        put(
            "messages",
            JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }
            )
        )
    }.toString()

    /**
     * English regardless of the app's language: it instructs the model, it is not
     * shown to anyone, and the prefixes it asks for are what [parseResponse] matches.
     */
    private fun buildPrompt(text: String) = """
        You are a German language expert. Translate the following German text to English.
        Also, extract 3-5 key German vocabulary words from the text.
        Finally, provide one natural conversation example sentence using one of those words.

        Text: $text

        Format the response exactly as follows, with no extra commentary:
        Translation: [English translation]
        Keywords: [word1, word2, word3]
        Example: [German example sentence]
    """.trimIndent()

    /**
     * Prefers the provider's own explanation over a bare status code.
     *
     * That explanation is passed through untranslated: it arrives in whatever
     * language the API speaks, and inventing a German rendering of a sentence we did
     * not write would be worse than showing the original.
     */
    private fun errorMessage(status: Int, body: String?): String =
        detailFrom(body) ?: when (status) {
            401 -> context.getString(R.string.ai_key_rejected)
            429 -> context.getString(R.string.ai_rate_limited)
            else -> context.getString(R.string.ai_status, status)
        }

    companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        /**
         * Model ids expire; this one is a maintenance item, not a preference. The app
         * shipped a Gemini model that was retired underneath it, and the only symptom
         * a user saw was "Translation failed", which reads like a bad API key.
         *
         * Groq's free tier covers this comfortably - roughly 1,000 requests a day,
         * against an app that makes one per spoken sentence.
         */
        const val MODEL_NAME = "llama-3.3-70b-versatile"

        private const val TIMEOUT_MS = 30_000

        // Prompt tokens, not UI text: these are matched against the model's reply and
        // stay English in every locale, because the prompt that asks for them does.
        private const val TRANSLATION_PREFIX = "Translation:"
        private const val KEYWORDS_PREFIX = "Keywords:"
        private const val EXAMPLE_PREFIX = "Example:"

        /** Pulls the assistant's text out of the OpenAI chat response shape. */
        internal fun contentOf(json: String): String =
            JSONObject(json)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()

        /** The provider's own error sentence, when the body carries one. */
        internal fun detailFrom(body: String?): String? = body
            ?.let { runCatching { JSONObject(it).optJSONObject("error")?.optString("message") } }
            ?.getOrNull()
            ?.takeIf { it.isNotBlank() }

        /**
         * Tolerates the markdown and list bullets the model adds unbidden - plain
         * `startsWith("Translation:")` silently produced three empty fields whenever
         * it answered with `**Translation:**`.
         *
         * Null rather than a Failure carrying prose: the caller owns the Context, and
         * so owns the wording. Provider-agnostic, which is why swapping Gemini for
         * Groq left it untouched.
         */
        internal fun parseResponse(text: String): AIResult.Success? {
            var translation = ""
            var keywords = emptyList<String>()
            var example = ""

            text.lineSequence().forEach { rawLine ->
                // Emphasis first, then bullets: stripping "*" off "**Translation:**"
                // would otherwise leave a stray leading asterisk behind.
                val line = rawLine.trim()
                    .replace("**", "")
                    .replace("__", "")
                    .removePrefix("-")
                    .removePrefix("*")
                    .trim()

                when {
                    line.startsWith(TRANSLATION_PREFIX, ignoreCase = true) ->
                        translation = line.drop(TRANSLATION_PREFIX.length).cleanValue()

                    line.startsWith(KEYWORDS_PREFIX, ignoreCase = true) ->
                        keywords = line.drop(KEYWORDS_PREFIX.length)
                            .cleanValue()
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }

                    line.startsWith(EXAMPLE_PREFIX, ignoreCase = true) ->
                        example = line.drop(EXAMPLE_PREFIX.length).cleanValue()
                }
            }

            return if (translation.isBlank()) {
                null
            } else {
                AIResult.Success(translation, keywords, example)
            }
        }

        private fun String.cleanValue() = trim().removeSurrounding("[", "]").trim()
    }
}

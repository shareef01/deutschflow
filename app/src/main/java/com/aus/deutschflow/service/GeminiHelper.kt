package com.aus.deutschflow.service

import com.google.ai.client.generativeai.GenerativeModel

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

class GeminiHelper {

    suspend fun translateAndExtract(text: String, apiKey: String): AIResult {
        if (apiKey.isBlank()) {
            return AIResult.Failure("Add your Gemini API key in Settings to get translations.")
        }

        return try {
            val model = GenerativeModel(modelName = MODEL_NAME, apiKey = apiKey)
            val response = model.generateContent(buildPrompt(text))
            parseResponse(response.text.orEmpty())
        } catch (e: Exception) {
            AIResult.Failure("Translation failed: ${e.message ?: "no response from Gemini"}")
        }
    }

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
     * Tolerates the markdown and list bullets the model adds unbidden - plain
     * `startsWith("Translation:")` silently produced three empty fields whenever it
     * answered with `**Translation:**`.
     */
    internal fun parseResponse(text: String): AIResult {
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
            AIResult.Failure("Couldn't read the response from Gemini. Try again.")
        } else {
            AIResult.Success(translation, keywords, example)
        }
    }

    private fun String.cleanValue() = trim().removeSurrounding("[", "]").trim()

    companion object {
        /**
         * Model ids expire. This one is not a preference, it is a maintenance item.
         *
         * The app shipped `gemini-1.5-flash` until the whole 1.5 line was shut down;
         * every request then returned 404 and the only symptom a user saw was
         * "Translation failed", which reads like a bad API key. Check
         * ai.google.dev/gemini-api/docs/deprecations before a release.
         *
         * 2.5-flash-lite rather than a 3.x model on purpose: this SDK's PartSerializer
         * throws on any response part without a key it recognises, and the 3.x line
         * attaches thought signatures to parts. 2.5-flash-lite has thinking off by
         * default and answers with plain text, which is what [parseResponse] expects.
         * Migrating to the Firebase AI Logic SDK is what makes newer models safe.
         */
        const val MODEL_NAME = "gemini-2.5-flash-lite"

        private const val TRANSLATION_PREFIX = "Translation:"
        private const val KEYWORDS_PREFIX = "Keywords:"
        private const val EXAMPLE_PREFIX = "Example:"
    }
}

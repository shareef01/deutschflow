package com.aus.deutschflow.service

import com.google.ai.client.generativeai.GenerativeModel

class GeminiHelper {

    suspend fun translateAndExtract(text: String, apiKey: String): AIResult {
        if (apiKey.isBlank()) {
            return AIResult(
                translation = "API Key Missing",
                keywords = emptyList(),
                example = "Please set your Gemini API Key in Settings"
            )
        }

        val model = GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = apiKey
        )

        val prompt = """
            You are a German language expert. Translate the following German text to English.
            Also, extract 3-5 key German vocabulary words from the text.
            Finally, provide one natural conversation example sentence using one of those words.
            
            Text: $text
            
            Format the response exactly as follows:
            Translation: [English translation]
            Keywords: [word1, word2, word3]
            Example: [German example sentence]
        """.trimIndent()

        return try {
            val response = model.generateContent(prompt)
            parseResponse(response.text ?: "")
        } catch (e: Exception) {
            AIResult("Error: ${e.message}", emptyList(), "")
        }
    }

    private fun parseResponse(text: String): AIResult {
        var translation = ""
        var keywords = emptyList<String>()
        var example = ""

        text.lines().forEach { line ->
            when {
                line.startsWith("Translation:") -> translation = line.removePrefix("Translation:").trim()
                line.startsWith("Keywords:") -> {
                    keywords = line.removePrefix("Keywords:").trim()
                        .removeSurrounding("[", "]")
                        .split(",")
                        .filter { it.isNotBlank() }
                        .map { it.trim() }
                }
                line.startsWith("Example:") -> example = line.removePrefix("Example:").trim()
            }
        }

        return AIResult(translation, keywords, example)
    }
}

data class AIResult(
    val translation: String,
    val keywords: List<String>,
    val example: String
)

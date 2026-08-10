package com.aus.deutschflow.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqHelperTest {

    private val helper = GroqHelper()

    @Test
    fun `parses the format the prompt asks for`() {
        val response = """
            Translation: I am learning German.
            Keywords: [lernen, Deutsch, heute]
            Example: Ich lerne jeden Tag Deutsch.
        """.trimIndent()

        val result = helper.parseResponse(response)

        assertTrue(result is AIResult.Success)
        result as AIResult.Success
        assertEquals("I am learning German.", result.translation)
        assertEquals(listOf("lernen", "Deutsch", "heute"), result.keywords)
        assertEquals("Ich lerne jeden Tag Deutsch.", result.example)
    }

    @Test
    fun `tolerates markdown emphasis and bullets`() {
        val response = """
            **Translation:** I am learning German.
            - **Keywords:** lernen, Deutsch
            * Example: Ich lerne Deutsch.
        """.trimIndent()

        val result = helper.parseResponse(response)

        assertTrue(result is AIResult.Success)
        result as AIResult.Success
        assertEquals("I am learning German.", result.translation)
        assertEquals(listOf("lernen", "Deutsch"), result.keywords)
        assertEquals("Ich lerne Deutsch.", result.example)
    }

    @Test
    fun `reports failure instead of an empty success when the format is unrecognised`() {
        val result = helper.parseResponse("Sure! Here is your translation, hope it helps.")

        assertTrue(result is AIResult.Failure)
    }

    @Test
    fun `reports failure when no api key is set`() {
        val result = kotlinx.coroutines.runBlocking {
            helper.translateAndExtract(text = "Hallo", apiKey = "")
        }

        assertTrue(result is AIResult.Failure)
    }

    // --- the OpenAI chat response shape --------------------------------------

    @Test
    fun `pulls the assistant message out of a chat completion`() {
        val body = """
            {"id":"x","choices":[{"index":0,"message":{"role":"assistant",
            "content":"Translation: I am learning German."},"finish_reason":"stop"}]}
        """.trimIndent()

        assertEquals("Translation: I am learning German.", helper.contentOf(body))
    }

    @Test
    fun `a response with no choices yields empty content rather than throwing`() {
        // Which parseResponse then turns into a Failure, so the user is told
        // something went wrong instead of the app falling over.
        assertEquals("", helper.contentOf("""{"id":"x","choices":[]}"""))

        assertTrue(helper.parseResponse(helper.contentOf("""{"id":"x","choices":[]}""")) is AIResult.Failure)
    }

    // --- error reporting ------------------------------------------------------

    @Test
    fun `prefers the provider's own explanation`() {
        val body = """{"error":{"message":"Invalid API Key","type":"invalid_request_error"}}"""

        assertEquals("Invalid API Key", helper.errorMessage(401, body))
    }

    @Test
    fun `falls back to a plain explanation when the body is not the expected shape`() {
        assertEquals("That API key was rejected. Check it in Settings.", helper.errorMessage(401, null))
        assertEquals("Too many requests for now. Try again in a minute.", helper.errorMessage(429, "<html>"))
        assertEquals("The service answered with 500.", helper.errorMessage(500, ""))
    }
}

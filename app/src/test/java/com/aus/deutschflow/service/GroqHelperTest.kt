package com.aus.deutschflow.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the parts of the client that hold no Context, which is deliberately all of
 * the parsing. The wording of a failure now comes from resources, so the sentences
 * themselves are asserted where a Context exists - TranscriptViewModelTest.
 */
class GroqHelperTest {

    // --- the response the prompt asks for -------------------------------------

    @Test
    fun `parses the format the prompt asks for`() {
        val response = """
            Translation: I am learning German.
            Keywords: [lernen, Deutsch, heute]
            Example: Ich lerne jeden Tag Deutsch.
        """.trimIndent()

        val result = GroqHelper.parseResponse(response)

        assertNotNull(result)
        assertEquals("I am learning German.", result!!.translation)
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

        val result = GroqHelper.parseResponse(response)

        assertNotNull(result)
        assertEquals("I am learning German.", result!!.translation)
        assertEquals(listOf("lernen", "Deutsch"), result.keywords)
        assertEquals("Ich lerne Deutsch.", result.example)
    }

    @Test
    fun `returns null rather than an empty success when the format is unrecognised`() {
        // Null, so the caller - which owns the Context, and so the wording - decides
        // what to tell the user. An empty Success would have been storable as a
        // translation by the Save button.
        assertNull(GroqHelper.parseResponse("Sure! Here is your translation, hope it helps."))
    }

    // --- the OpenAI chat response shape ---------------------------------------

    @Test
    fun `pulls the assistant message out of a chat completion`() {
        val body = """
            {"id":"x","choices":[{"index":0,"message":{"role":"assistant",
            "content":"Translation: I am learning German."},"finish_reason":"stop"}]}
        """.trimIndent()

        assertEquals("Translation: I am learning German.", GroqHelper.contentOf(body))
    }

    @Test
    fun `a response with no choices yields empty content rather than throwing`() {
        val empty = GroqHelper.contentOf("""{"id":"x","choices":[]}""")

        assertEquals("", empty)
        // Which the caller then reports as unreadable, instead of falling over.
        assertNull(GroqHelper.parseResponse(empty))
    }

    // --- error reporting ------------------------------------------------------

    @Test
    fun `lifts the provider's own explanation out of an error body`() {
        val body = """{"error":{"message":"Invalid API Key","type":"invalid_request_error"}}"""

        assertEquals("Invalid API Key", GroqHelper.detailFrom(body))
    }

    @Test
    fun `yields null when the body carries no explanation`() {
        // The caller falls back to a translated sentence for the status code.
        assertNull(GroqHelper.detailFrom(null))
        assertNull(GroqHelper.detailFrom("<html>not json</html>"))
        assertNull(GroqHelper.detailFrom(""))
        assertNull(GroqHelper.detailFrom("""{"error":{"message":""}}"""))
    }
}

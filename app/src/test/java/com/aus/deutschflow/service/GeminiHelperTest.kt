package com.aus.deutschflow.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiHelperTest {

    private val helper = GeminiHelper()

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
}

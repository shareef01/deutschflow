package com.aus.deutschflow.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An answer the provider stopped at the token limit is told apart from a garbled one,
 * so the user is told to shorten the input rather than to try the same thing again.
 */
class GroqCutOffTest {

    private fun response(content: String, finishReason: String) =
        """{"choices":[{"message":{"role":"assistant","content":${org.json.JSONObject.quote(content)}},"finish_reason":"$finishReason"}]}"""

    @Test
    fun `an answer stopped at the limit is reported as cut off`() {
        val raw = response("""{"translation":"I am lea""", "length")

        assertTrue(GroqHelper.wasCutOff(raw))
        // And it is exactly the case the parser cannot read, which is when it matters.
        assertNull(GroqHelper.parseResponse(GroqHelper.contentOf(raw)))
    }

    @Test
    fun `an empty answer at the limit is cut off too`() {
        // A reasoning model can spend the whole budget before writing any content.
        assertTrue(GroqHelper.wasCutOff(response("", "length")))
    }

    @Test
    fun `a finished answer is not cut off`() {
        assertFalse(GroqHelper.wasCutOff(response("""{"translation":"Hi"}""", "stop")))
    }

    @Test
    fun `a body without choices or that is not JSON is not cut off`() {
        assertFalse(GroqHelper.wasCutOff("""{"error":{"message":"bad key"}}"""))
        assertFalse(GroqHelper.wasCutOff("<html>gateway timeout</html>"))
        assertFalse(GroqHelper.wasCutOff(""))
    }
}

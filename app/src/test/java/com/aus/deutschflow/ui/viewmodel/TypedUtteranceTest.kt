package com.aus.deutschflow.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gate typed German passes before it joins the speech pipeline
 * ([TranscriptViewModel.submitTypedText]).
 */
class TypedUtteranceTest {

    private fun gate(text: String, isListening: Boolean = false, isBusy: Boolean = false) =
        TranscriptViewModel.typedUtterance(text, isListening, isBusy)

    @Test
    fun `idle text is accepted as typed`() {
        assertEquals("Wie geht es dir heute?", gate("Wie geht es dir heute?"))
    }

    @Test
    fun `surrounding whitespace is trimmed, inner spacing kept`() {
        assertEquals("Ich lerne  Deutsch", gate("  Ich lerne  Deutsch \n"))
    }

    @Test
    fun `empty and blank text are refused`() {
        assertNull(gate(""))
        assertNull(gate("   \t\n"))
    }

    @Test
    fun `refused while recording, so the recognizer's result cannot replace it`() {
        assertNull(gate("Hallo", isListening = true))
    }

    @Test
    fun `refused while a translation is in flight`() {
        assertNull(gate("Hallo", isBusy = true))
    }
}

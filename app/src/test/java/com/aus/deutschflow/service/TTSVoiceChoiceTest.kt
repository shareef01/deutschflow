package com.aus.deutschflow.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * AUDIT_2026-10-12 F-15: the read-aloud voice used to be the first offline German
 * voice the engine listed, whatever dialect the learner chose in Settings.
 */
class TTSVoiceChoiceTest {

    private data class FakeVoice(val name: String, val tag: String, val network: Boolean = false)

    private fun choose(voices: List<FakeVoice>, dialect: String) = TTSHelper.chooseVoice(
        voices,
        dialect,
        locale = { Locale.forLanguageTag(it.tag) },
        needsNetwork = { it.network }
    )

    private val swiss = FakeVoice("swiss", "de-CH")
    private val austrian = FakeVoice("austrian", "de-AT")
    private val standard = FakeVoice("standard", "de-DE")
    private val english = FakeVoice("english", "en-US")

    @Test
    fun theChosenDialectWinsOverListOrder() {
        val voices = listOf(swiss, standard, austrian)
        assertEquals(austrian, choose(voices, "de-AT"))
        assertEquals(swiss, choose(voices, "de-CH"))
        assertEquals(standard, choose(voices, "de-DE"))
    }

    @Test
    fun aMissingDialectFallsBackToStandardGermanThenAnyGerman() {
        assertEquals(standard, choose(listOf(swiss, standard), "de-AT"))
        assertEquals(swiss, choose(listOf(english, swiss), "de-AT"))
    }

    @Test
    fun aNetworkVoiceIsNeverChosenEvenWhenItMatches() {
        val networkAustrian = FakeVoice("cloud-at", "de-AT", network = true)
        assertEquals(standard, choose(listOf(networkAustrian, standard), "de-AT"))
        assertNull(choose(listOf(networkAustrian, english), "de-AT"))
    }

    @Test
    fun noGermanVoiceMeansNoChoice() {
        assertNull(choose(listOf(english), "de-DE"))
        assertNull(choose(emptyList(), "de-DE"))
    }
}

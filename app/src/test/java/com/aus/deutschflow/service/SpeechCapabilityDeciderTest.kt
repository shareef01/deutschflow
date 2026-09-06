package com.aus.deutschflow.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Verifies on-device speech capability decisions and language support checks.
 *
 * Requirements:
 * - no on-device service => no factory call (NO_ON_DEVICE_SERVICE)
 * - on-device service => factory path (START_LISTENING)
 * - downloadable German language => download flow (DOWNLOAD_MODEL)
 * - unsupported German language => clear error (UNSUPPORTED_LANGUAGE)
 * - no cloud fallback path exists
 */
class SpeechCapabilityDeciderTest {

    @Test
    fun `no on-device service returns NO_ON_DEVICE_SERVICE and prevents factory call`() {
        val decision = SpeechCapabilityDecider.decide(
            isOnDeviceAvailable = false,
            languageTag = "de-DE",
            installedLanguages = listOf("de-DE"),
            supportedLanguages = listOf("de-DE")
        )
        assertEquals(SpeechCapabilityDecision.NO_ON_DEVICE_SERVICE, decision)
    }

    @Test
    fun `on-device service with installed language proceeds to START_LISTENING`() {
        val decision = SpeechCapabilityDecider.decide(
            isOnDeviceAvailable = true,
            languageTag = "de-DE",
            installedLanguages = listOf("de-DE", "en-US"),
            supportedLanguages = listOf("de-DE", "en-US", "fr-FR")
        )
        assertEquals(SpeechCapabilityDecision.START_LISTENING, decision)
    }

    @Test
    fun `downloadable German language model returns DOWNLOAD_MODEL`() {
        val decision = SpeechCapabilityDecider.decide(
            isOnDeviceAvailable = true,
            languageTag = "de-DE",
            installedLanguages = listOf("en-US"),
            supportedLanguages = listOf("de-DE", "en-US")
        )
        assertEquals(SpeechCapabilityDecision.DOWNLOAD_MODEL, decision)
    }

    @Test
    fun `unsupported German language dialect returns UNSUPPORTED_LANGUAGE`() {
        val decision = SpeechCapabilityDecider.decide(
            isOnDeviceAvailable = true,
            languageTag = "de-CH",
            installedLanguages = listOf("en-US"),
            supportedLanguages = listOf("en-US", "es-ES")
        )
        assertEquals(SpeechCapabilityDecision.UNSUPPORTED_LANGUAGE, decision)
    }

    @Test
    fun `supports Austrian and Swiss German dialect tags with underscore or hyphen`() {
        val decisionAt = SpeechCapabilityDecider.decide(
            isOnDeviceAvailable = true,
            languageTag = "de-AT",
            installedLanguages = listOf("de_AT"),
            supportedLanguages = listOf("de_AT")
        )
        assertEquals(SpeechCapabilityDecision.START_LISTENING, decisionAt)

        val decisionCh = SpeechCapabilityDecider.decide(
            isOnDeviceAvailable = true,
            languageTag = "de-CH",
            installedLanguages = emptyList(),
            supportedLanguages = listOf("de_CH")
        )
        assertEquals(SpeechCapabilityDecision.DOWNLOAD_MODEL, decisionCh)
    }

    @Test
    fun `API under 33 with null language lists proceeds directly to START_LISTENING`() {
        val decision = SpeechCapabilityDecider.decide(
            isOnDeviceAvailable = true,
            languageTag = "de-DE",
            installedLanguages = null,
            supportedLanguages = null
        )
        assertEquals(SpeechCapabilityDecision.START_LISTENING, decision)
    }

    @Test
    fun `decisions never produce a cloud fallback state`() {
        for (available in listOf(true, false)) {
            for (lang in listOf("de-DE", "de-AT", "de-CH", "unsupported")) {
                val d = SpeechCapabilityDecider.decide(
                    available,
                    lang,
                    listOf("de-DE"),
                    listOf("de-DE", "de-AT")
                )
                // The only permitted decisions are pure on-device decisions
                assertNotEquals("Should never produce a cloud fallback", "CLOUD_FALLBACK", d.name)
            }
        }
    }
}

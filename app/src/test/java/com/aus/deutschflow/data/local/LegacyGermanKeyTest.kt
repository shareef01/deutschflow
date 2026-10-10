package com.aus.deutschflow.data.local

import com.aus.deutschflow.data.local.entities.germanKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The fold MIGRATION_12_13 and MIGRATION_14_15 re-key through is frozen.
 *
 * Those two migrations rewrite every `germanTextKey` and merge whatever collides, so
 * their result must depend only on the schema they bridge, never on the live
 * [germanKey]. The fold used to be derived as `germanKey(text).replace("ß", "ss")`,
 * which stays the historical fold only while the normalisation and umlaut rules are
 * shared with the live function - a later change to any of them would have re-keyed
 * both migrations silently.
 *
 * [legacyGermanKey] is now written out in full, and these literals pin it. They are
 * the outputs v13 and v15 produced, so they must not move; if this fails, a shipped
 * migration's meaning has changed and installs upgrading through it would be re-keyed
 * by today's rules.
 */
class LegacyGermanKeyTest {

    @Test
    fun theHistoricalFoldStillFoldsSharpSToDoubleS() {
        // The one rule that separates this fold from the identity key: germanKey keeps
        // ß (see GermanKeyTest.eszettIsNotFoldedToDoubleS), this fold does not.
        assertEquals("strasse", legacyGermanKey("Straße"))
        assertEquals("masse", legacyGermanKey("Maße"))
        assertEquals("busse", legacyGermanKey("Buße"))
        assertNotEquals(germanKey("Straße"), legacyGermanKey("Straße"))
    }

    @Test
    fun theHistoricalFoldStillTransliteratesUmlautsAndNormalises() {
        assertEquals("uebung", legacyGermanKey("Übung"))
        assertEquals("uebung", legacyGermanKey("Uebung"))
        assertEquals("aerger", legacyGermanKey("Ärger"))
        assertEquals("oel", legacyGermanKey("Öl"))
        // Decomposed input normalises to the same key.
        assertEquals("uebung", legacyGermanKey("U\u0308bung"))
        // Case is folded and surrounding whitespace trimmed, as the fold has always done.
        assertEquals("das haus", legacyGermanKey("  das Haus  "))
    }
}

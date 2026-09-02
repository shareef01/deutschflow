package com.aus.deutschflow.data.local

import com.aus.deutschflow.data.local.entities.germanKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Duplicate detection, for a language with umlauts.
 *
 * Uniqueness used to be SQLite's NOCASE collation, which folds ASCII A-Z and
 * nothing else - so "Hund" and "hund" were one word while "Übung" and "übung" were
 * two. Every umlaut-initial German noun escaped deduplication.
 *
 * The table below is asserted identically in web/tests/germanfold.test.ts. The two
 * folds are the same rule written twice, and this is what keeps them honest.
 */
class GermanKeyTest {

    @Test
    fun caseIsFolded_includingUmlauts() {
        assertEquals(germanKey("Hund"), germanKey("hund"))
        assertEquals(germanKey("Übung"), germanKey("übung"))
        assertEquals(germanKey("Öl"), germanKey("öl"))
        assertEquals(germanKey("Ärger"), germanKey("ärger"))
    }

    @Test
    fun transliteratedSpellingsAreTheSameWord() {
        assertEquals(germanKey("Übung"), germanKey("Uebung"))
        assertEquals(germanKey("Straße"), germanKey("Strasse"))
        assertEquals(germanKey("schön"), germanKey("schoen"))
    }

    @Test
    fun genuinelyDifferentWordsStayApart() {
        assertNotEquals(germanKey("Hund"), germanKey("Hand"))
        assertNotEquals(germanKey("schon"), germanKey("schön"))
    }

    /** The shared table. Any change here must change germanfold.test.ts too. */
    @Test
    fun theSharedFixtureMatchesTheWebFold() {
        val table = listOf(
            "Hund" to "hund",
            "Übung" to "uebung",
            "übung" to "uebung",
            "Uebung" to "uebung",
            "Straße" to "strasse",
            "Strasse" to "strasse",
            "Öl" to "oel",
            "Ärger" to "aerger",
            "  das Haus  " to "das haus"
        )

        for ((given, expected) in table) {
            assertEquals("germanKey(\"$given\")", expected, germanKey(given))
        }
    }
}

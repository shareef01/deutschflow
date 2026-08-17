package com.aus.deutschflow.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyProcessorTest {

    @Test
    fun `generateExample returns a sentence containing the word`() {
        val word = "Schule"

        repeat(20) {
            assertTrue(VocabularyProcessor.generateExample(word).contains(word))
        }
    }

    @Test
    fun `generateExample uses the hand written sentence for known words`() {
        assertTrue(VocabularyProcessor.generateExample("hallo").contains("wie geht es dir"))
        assertTrue(VocabularyProcessor.generateExample("Deutsch").contains("jeden Tag"))
    }

    /**
     * The library screen calls this during composition, so a random pick meant a
     * word's example changed as the user scrolled past it. The sentence is a
     * property of the word, so asking twice must answer the same.
     */
    @Test
    fun `generateExample answers the same sentence for the same word`() {
        val first = VocabularyProcessor.generateExample("Wortschatz")

        repeat(20) {
            assertEquals(first, VocabularyProcessor.generateExample("Wortschatz"))
        }
    }

    /** Stable is not the same as constant: the library should not read as one sentence. */
    @Test
    fun `generateExample spreads different words across the templates`() {
        val words = listOf(
            "Wortschatz", "Fenster", "Tisch", "Buch", "Katze",
            "Hund", "Baum", "Wasser", "Stadt", "Freund", "Arbeit", "Zeit"
        )

        val distinct = words.map { VocabularyProcessor.generateExample(it).replace(it, "") }
            .distinct()

        assertTrue("Expected several templates in use, got ${distinct.size}", distinct.size > 3)
    }

    /**
     * hashCode is signed, so a word that hashes negative would index out of the
     * template list if the fold were a plain `%` rather than floorMod.
     */
    @Test
    fun `generateExample survives words whose hash is negative`() {
        val negative = generateSequence(1) { it + 1 }
            .map { "wort$it" }
            .first { it.hashCode() < 0 }

        assertTrue(VocabularyProcessor.generateExample(negative).contains(negative))
    }
}

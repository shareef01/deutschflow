package com.aus.deutschflow.data.local

import com.aus.deutschflow.data.local.entities.VocabularyEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides what a word looks like after being met twice.
 *
 * A pure function over a plain data class, so it is tested here rather than through a
 * database - and it is worth testing on its own because MIGRATION_6_7 has to reproduce
 * it in SQL for the duplicates that already exist, and the two have to agree.
 */
class VocabularyMergeTest {

    private fun word(
        id: Int = 1,
        german: String = "Hund",
        english: String = "dog",
        timestamp: Long = 1_000,
        example: String = "",
        article: String = "",
        plural: String = "",
        conjugation: String = ""
    ) = VocabularyEntity(
        id = id,
        germanText = german,
        englishTranslation = english,
        timestamp = timestamp,
        exampleSentence = example,
        article = article,
        plural = plural,
        conjugation = conjugation
    )

    @Test
    fun `an interrogation fills in the grammar a hand-typed word lacked`() {
        val typed = word(english = "dog")
        val interrogated = word(
            id = 0,
            english = "dog",
            timestamp = 2_000,
            example = "Der Hund schläft.",
            article = "der",
            plural = "Hunde"
        )

        val merged = typed.mergedWith(interrogated)

        assertEquals("der", merged.article)
        assertEquals("Hunde", merged.plural)
        assertEquals("Der Hund schläft.", merged.exampleSentence)
    }

    @Test
    fun `a blank field in the newcomer never erases what was already known`() {
        val known = word(
            example = "Der Hund schläft.",
            article = "der",
            plural = "Hunde",
            conjugation = "laufen"
        )

        // A word saved again from a transcript carries none of the grammar.
        val merged = known.mergedWith(word(id = 0, example = "", article = "", plural = ""))

        assertEquals("der", merged.article)
        assertEquals("Hunde", merged.plural)
        assertEquals("laufen", merged.conjugation)
        assertEquals("Der Hund schläft.", merged.exampleSentence)
    }

    @Test
    fun `the surviving row keeps its own id and spelling`() {
        val existing = word(id = 7, german = "Hund")

        val merged = existing.mergedWith(word(id = 0, german = "hund"))

        assertEquals(7, merged.id)
        // Re-saving a word should not silently recapitalise it in the library.
        assertEquals("Hund", merged.germanText)
    }

    @Test
    fun `the later timestamp wins, whichever side it is on`() {
        assertEquals(2_000, word(timestamp = 1_000).mergedWith(word(timestamp = 2_000)).timestamp)
        // An older sighting must not drag a word back down the list.
        assertEquals(2_000, word(timestamp = 2_000).mergedWith(word(timestamp = 1_000)).timestamp)
    }

    @Test
    fun `a newcomer's translation replaces the old one when it has any`() {
        val merged = word(english = "dog").mergedWith(word(id = 0, english = "hound"))

        assertEquals("hound", merged.englishTranslation)
    }
}

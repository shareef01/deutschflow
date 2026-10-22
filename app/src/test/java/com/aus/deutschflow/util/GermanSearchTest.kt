package com.aus.deutschflow.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library and history search, as people type German. Same cases as
 * web/tests/search.test.ts, so the two clients find the same rows.
 */
class GermanSearchTest {

    private fun finds(text: String, query: String): Boolean =
        requireNotNull(GermanQuery.prepare(query)) { "blank query" }.matchesGerman(text)

    @Test
    fun `finds a word from each way people type it`() {
        for (query in listOf("Mädchen", "mädchen", "maedchen", "madchen", "MADCHEN")) {
            assertTrue(query, finds("das Mädchen", query))
        }
    }

    @Test
    fun `finds ß from ss, and the reverse`() {
        assertTrue(finds("die Straße", "strasse"))
        assertTrue(finds("die Strasse", "straße"))
    }

    @Test
    fun `matches substrings, not just whole words`() {
        assertTrue(finds("Ich möchte einen Kaffee", "mochte ein"))
    }

    @Test
    fun `does not match unrelated text`() {
        assertFalse(finds("der Hund", "katze"))
        assertFalse(finds("die Tür", "tor"))
    }

    @Test
    fun `lets an umlaut and its plain vowel meet, which is fine for a search box`() {
        assertTrue(finds("schon", "schön"))
    }

    @Test
    fun `treats a blank query as no filter`() {
        assertNull(GermanQuery.prepare("   "))
        assertNull(GermanQuery.prepare(""))
    }

    @Test
    fun `ignores surrounding whitespace in the query`() {
        assertTrue(finds("das Mädchen", "  madchen "))
    }

    @Test
    fun `matches the English side case-insensitively`() {
        val q = GermanQuery.prepare("Apple")!!
        assertTrue(q.matchesPlain("the apple"))
        assertFalse(q.matchesPlain(""))
    }
}

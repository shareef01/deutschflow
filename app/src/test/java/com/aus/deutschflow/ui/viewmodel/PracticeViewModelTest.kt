package com.aus.deutschflow.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeViewModelTest {

    // --- evaluateMatch --------------------------------------------------------

    @Test
    fun `exact match returns perfect score for every word`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich lerne Deutsch",
            spokenText = "Ich lerne Deutsch"
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it.isCorrect })
        assertEquals(PracticeFeedback.PERFECT, feedback)
    }

    @Test
    fun `case difference is still an exact match`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich lerne Deutsch",
            spokenText = "ich lerne deutsch"
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it.isCorrect })
        assertEquals(PracticeFeedback.PERFECT, feedback)
    }

    @Test
    fun `extra spoken words do not hurt the score`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich lerne Deutsch",
            spokenText = "Hallo ich lerne Deutsch heute"
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it.isCorrect })
        assertEquals(PracticeFeedback.PERFECT, feedback)
    }

    @Test
    fun `partial match when half the words are spoken`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich lerne Deutsch",
            spokenText = "Ich lerne"
        )

        assertEquals(3, results.size)
        val correct = results.count { it.isCorrect }
        assertEquals(2, correct)
        assertTrue(results.first { it.word == "Ich" }.isCorrect)
        assertTrue(results.first { it.word == "lerne" }.isCorrect)
        assertFalse(results.first { it.word == "Deutsch" }.isCorrect)
        assertEquals(PracticeFeedback.GOOD, feedback)
    }

    @Test
    fun `no correct words gives keep-practicing feedback`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich lerne Deutsch",
            spokenText = "Guten Morgen"
        )

        assertEquals(3, results.size)
        assertTrue(results.none { it.isCorrect })
        assertEquals(PracticeFeedback.KEEP_GOING, feedback)
    }

    @Test
    fun `empty spoken text marks nothing correct`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich lerne Deutsch",
            spokenText = ""
        )

        assertEquals(3, results.size)
        assertTrue(results.none { it.isCorrect })
        assertEquals(PracticeFeedback.KEEP_GOING, feedback)
    }

    @Test
    fun `empty target returns empty results and empty feedback`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "",
            spokenText = "Ich lerne Deutsch"
        )

        assertTrue(results.isEmpty())
        assertEquals(PracticeFeedback.NONE, feedback)
    }

    @Test
    fun `punctuation is stripped from both sides`() {
        val (results, _) = PracticeViewModel.evaluateMatch(
            targetSentence = "Hallo, wie geht es dir?",
            spokenText = "Hallo wie geht es dir"
        )

        assertEquals(5, results.size)
        assertTrue(results.all { it.isCorrect })
    }

    @Test
    fun `German umlauts are preserved in matching`() {
        val (results, _) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich möchte üben",
            spokenText = "Ich möchte üben"
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it.isCorrect })
    }

    @Test
    fun `German sharp-s is preserved`() {
        val (results, _) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich heiße Max",
            spokenText = "Ich heiße Max"
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it.isCorrect })
    }

    @Test
    fun `word order does not matter`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich lerne Deutsch",
            spokenText = "Deutsch lerne Ich"
        )

        // All words are present regardless of order — this is a documented
        // limitation of the simple bag-of-words algorithm.
        assertEquals(3, results.size)
        assertTrue(results.all { it.isCorrect })
        assertEquals(PracticeFeedback.PERFECT, feedback)
    }

    @Test
    fun `just over half counts as good`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "eins zwei drei vier fünf",
            spokenText = "eins zwei drei"
        )

        assertEquals(5, results.size)
        assertEquals(3, results.count { it.isCorrect })
        // 3 of 5 > half, so "Good"
        assertEquals(PracticeFeedback.GOOD, feedback)
    }

    @Test
    fun `exactly half counts as keep practicing`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "eins zwei drei vier",
            spokenText = "eins zwei"
        )

        assertEquals(4, results.size)
        assertEquals(2, results.count { it.isCorrect })
        // 2 of 4 is exactly half (not > half), so keep-practicing
        assertEquals(PracticeFeedback.KEEP_GOING, feedback)
    }

    @Test
    fun `single word target perfect match`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "Hallo",
            spokenText = "Hallo"
        )

        assertEquals(1, results.size)
        assertTrue(results.first().isCorrect)
        assertEquals(PracticeFeedback.PERFECT, feedback)
    }

    // --- umlauts and their keyboard transliterations ------------------------
    //
    // The recogniser always returns the umlaut. A word typed on a keyboard without
    // one does not have it. Both spellings are the same word, and scoring them as
    // different is the failure this screen can least afford - it is the only thing
    // Practice judges.

    @Test
    fun `a target typed with ue matches speech that came back with u-umlaut`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "die Uebung",
            spokenText = "die Übung"
        )

        assertEquals(2, results.size)
        assertTrue("Uebung and Übung are the same word", results.all { it.isCorrect })
        assertEquals(PracticeFeedback.PERFECT, feedback)
    }

    @Test
    fun `a target with an umlaut matches speech transliterated the other way`() {
        val (results, _) = PracticeViewModel.evaluateMatch(
            targetSentence = "die Übung",
            spokenText = "die Uebung"
        )

        assertTrue(results.all { it.isCorrect })
    }

    @Test
    fun `oe and ae fold the same way`() {
        val (results, _) = PracticeViewModel.evaluateMatch(
            targetSentence = "schoene Baeume",
            spokenText = "schöne Bäume"
        )

        assertEquals(2, results.size)
        assertTrue(results.all { it.isCorrect })
    }

    @Test
    fun `sharp-s matches its ss spelling`() {
        val (results, _) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich heisse Max",
            spokenText = "Ich heiße Max"
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it.isCorrect })
    }

    @Test
    fun `the word is reported as the user wrote it, not as it was folded`() {
        val (results, _) = PracticeViewModel.evaluateMatch(
            targetSentence = "die Übung",
            spokenText = "die Uebung"
        )

        // The screen renders this back. Folding is for comparison only.
        assertEquals("Übung", results[1].word)
    }

    @Test
    fun `folding does not make different words match`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "schoen",
            spokenText = "schon"
        )

        // schön and schon are genuinely different words; only the first folds to schoen.
        assertEquals(1, results.size)
        assertFalse(results.first().isCorrect)
        assertEquals(PracticeFeedback.KEEP_GOING, feedback)
    }

    @Test
    fun `whitespace-only spoken text is treated as empty`() {
        val (results, feedback) = PracticeViewModel.evaluateMatch(
            targetSentence = "Ich lerne Deutsch",
            spokenText = "   "
        )

        assertEquals(3, results.size)
        assertTrue(results.none { it.isCorrect })
        assertEquals(PracticeFeedback.KEEP_GOING, feedback)
    }
}

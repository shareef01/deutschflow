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

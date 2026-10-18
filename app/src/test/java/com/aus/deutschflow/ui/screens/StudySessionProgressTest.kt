package com.aus.deutschflow.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The session counter and progress bar, including cards that come back after Again.
 *
 * The same arithmetic lives in web/src/app/(app)/study/page.tsx.
 */
class StudySessionProgressTest {

    @Test
    fun `a fresh session starts at card one with an empty bar`() {
        val p = studySessionProgress(initialSize = 3, reviewed = 0, remaining = 3)
        assertEquals(3, p.totalInSession)
        assertEquals(1, p.currentCardNumber)
        assertEquals(0f, p.fraction, 0f)
    }

    @Test
    fun `an Again card grows the total instead of filling the bar early`() {
        // Three cards; the first was rated Again and re-queued, the next two Good.
        val p = studySessionProgress(initialSize = 3, reviewed = 3, remaining = 1)
        assertEquals(4, p.totalInSession)
        assertEquals(4, p.currentCardNumber)
        assertEquals(0.75f, p.fraction, 0.0001f)
    }

    @Test
    fun `answering the last card completes the bar`() {
        val p = studySessionProgress(initialSize = 3, reviewed = 4, remaining = 0)
        assertEquals(4, p.totalInSession)
        assertEquals(1f, p.fraction, 0f)
    }

    @Test
    fun `an empty session does not divide by zero`() {
        val p = studySessionProgress(initialSize = 0, reviewed = 0, remaining = 0)
        assertEquals(0, p.totalInSession)
        assertEquals(0, p.currentCardNumber)
        assertEquals(0f, p.fraction, 0f)
    }
}

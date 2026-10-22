package com.aus.deutschflow.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Dashboard's due banner count. Mirrors remainingDueCount in web/src/hooks/useStudy.ts. */
class RemainingDueCountTest {

    @Test
    fun `a scheduled sitting counts the cards still queued`() {
        assertEquals(3, StudyViewModel.remainingDueCount(queueSize = 3, isExtraPractice = false))
    }

    @Test
    fun `the count falls as cards leave the queue and reaches zero when done`() {
        val counts = (3 downTo 0).map { StudyViewModel.remainingDueCount(it, isExtraPractice = false) }
        assertEquals(listOf(3, 2, 1, 0), counts)
    }

    @Test
    fun `extra practice has nothing due however many cards it holds`() {
        assertEquals(0, StudyViewModel.remainingDueCount(queueSize = 40, isExtraPractice = true))
    }
}

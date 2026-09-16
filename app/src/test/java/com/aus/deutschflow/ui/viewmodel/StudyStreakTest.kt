package com.aus.deutschflow.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StudyStreakTest {

    @Test
    fun `review elapsed time starts at the previous review rather than card creation`() {
        val now = 1_900_000_000_000L
        assertEquals(0, StudyViewModel.elapsedReviewDays(null, now))
        assertEquals(2, StudyViewModel.elapsedReviewDays(now - 2 * 86_400_000L, now))
        assertEquals(0, StudyViewModel.elapsedReviewDays(now + 86_400_000L, now))
    }

    private fun at(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val monday9am = at(LocalDateTime.of(2026, 8, 3, 9, 0))

    @Test
    fun `first ever session starts the streak at one`() {
        assertEquals(1, StudyViewModel.nextStreak(currentStreak = 0, lastActivity = 0L, now = monday9am))
    }

    @Test
    fun `a second session the same day does not extend the streak`() {
        val laterSameDay = at(LocalDateTime.of(2026, 8, 3, 21, 30))

        assertEquals(4, StudyViewModel.nextStreak(currentStreak = 4, lastActivity = monday9am, now = laterSameDay))
    }

    @Test
    fun `the next calendar day extends the streak even after less than 24 hours`() {
        val tuesday8am = at(LocalDateTime.of(2026, 8, 4, 8, 0))

        assertEquals(5, StudyViewModel.nextStreak(currentStreak = 4, lastActivity = monday9am, now = tuesday8am))
    }

    @Test
    fun `missing a day breaks the streak`() {
        val thursday9am = at(LocalDateTime.of(2026, 8, 6, 9, 0))

        assertEquals(1, StudyViewModel.nextStreak(currentStreak = 4, lastActivity = monday9am, now = thursday9am))
    }
}

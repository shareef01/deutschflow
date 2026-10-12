package com.aus.deutschflow.data.local

import com.aus.deutschflow.data.local.entities.UserStatsEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The streak the screens show decays on its own; the stored one only changes when XP
 * is earned. See [UserStatsEntity.currentStreak].
 */
class CurrentStreakTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(berlin).toInstant().toEpochMilli()

    private fun stats(streak: Int, lastActivity: Long) =
        UserStatsEntity(streak = streak, lastActivityTimestamp = lastActivity)

    @Test
    fun `studied today keeps the streak`() {
        val s = stats(10, at(2026, 10, 12, 8))
        assertEquals(10, s.currentStreak(now = at(2026, 10, 12, 22), zone = berlin))
    }

    @Test
    fun `studied yesterday keeps it, because today can still extend it`() {
        val s = stats(10, at(2026, 10, 11, 23, 59))
        assertEquals(10, s.currentStreak(now = at(2026, 10, 12, 23, 59), zone = berlin))
    }

    @Test
    fun `a whole missed day ends it`() {
        val s = stats(10, at(2026, 10, 10, 23, 59))
        assertEquals(0, s.currentStreak(now = at(2026, 10, 12, 0, 1), zone = berlin))
    }

    @Test
    fun `days away for longer show zero, not the old number`() {
        val s = stats(10, at(2026, 10, 1, 9))
        assertEquals(0, s.currentStreak(now = at(2026, 10, 12, 9), zone = berlin))
    }

    @Test
    fun `calendar days, not 24-hour periods, across a DST change`() {
        // Clocks go back on 25 Oct 2026 in Berlin, so this span is 25 hours long but
        // still only one calendar day.
        val s = stats(4, at(2026, 10, 24, 23, 30))
        assertEquals(4, s.currentStreak(now = at(2026, 10, 25, 23, 30), zone = berlin))
    }

    @Test
    fun `never studied is zero`() {
        assertEquals(0, UserStatsEntity().currentStreak(now = at(2026, 10, 12, 9), zone = berlin))
        assertEquals(0, stats(5, 0L).currentStreak(now = at(2026, 10, 12, 9), zone = berlin))
    }
}

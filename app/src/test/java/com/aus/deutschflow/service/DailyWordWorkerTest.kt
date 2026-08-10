package com.aus.deutschflow.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyWordWorkerTest {

    private fun at(hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, 8, 10, hour, minute, 0, 0, ZoneId.of("Europe/Berlin"))

    private fun hoursFrom(now: ZonedDateTime): Double =
        DailyWordWorker.delayUntilNext(9, now) / 3_600_000.0

    @Test
    fun `before the slot, it waits until later the same day`() {
        assertEquals(2.0, hoursFrom(at(7, 0)), 0.001)
    }

    @Test
    fun `after the slot, it waits until tomorrow`() {
        assertEquals(23.0, hoursFrom(at(10, 0)), 0.001)
    }

    @Test
    fun `installing during the slot's own hour still waits for tomorrow`() {
        // 9:30 is past 9:00, so firing "today" would mean firing immediately.
        assertEquals(23.5, hoursFrom(at(9, 30)), 0.001)
    }

    @Test
    fun `the delay is never zero or negative`() {
        for (hour in 0..23) {
            for (minute in listOf(0, 30, 59)) {
                val delay = DailyWordWorker.delayUntilNext(9, at(hour, minute))
                assert(delay > 0) { "delay at $hour:$minute was $delay" }
                assert(delay <= 24 * 3_600_000L) { "delay at $hour:$minute exceeded a day" }
            }
        }
    }
}

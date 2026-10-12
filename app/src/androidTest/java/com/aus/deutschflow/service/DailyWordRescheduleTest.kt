package com.aus.deutschflow.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.TestPreferencesRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The re-anchor the daily worker now runs after every post.
 *
 * Running it from the worker is only safe if it is idempotent - every run calls it,
 * and only a changed offset may move the schedule - and if it records the offset it
 * moved to. Otherwise each run would re-anchor again, cancelling itself every day.
 */
@RunWith(AndroidJUnit4::class)
class DailyWordRescheduleTest {

    @get:Rule
    val store = TestPreferencesRule("daily-word-reschedule-test")

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Either side of Berlin's October 2026 DST change: +02:00, then +01:00.
    private val summer = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"))
    private val winter = ZonedDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"))

    @Test
    fun aChangedOffsetMovesTheScheduleOnceAndIsRecorded() = runBlocking {
        val prefs = store.preferences

        assertTrue("first sighting anchors", DailyWordWorker.rescheduleIfZoneChanged(context, prefs, summer))
        assertEquals(2 * 3600, prefs.dailyWordZoneOffset.first())
        assertFalse("same offset: nothing to move", DailyWordWorker.rescheduleIfZoneChanged(context, prefs, summer))

        assertTrue("the DST change re-anchors", DailyWordWorker.rescheduleIfZoneChanged(context, prefs, winter))
        assertEquals(3600, prefs.dailyWordZoneOffset.first())
        assertFalse("and only once", DailyWordWorker.rescheduleIfZoneChanged(context, prefs, winter))
    }
}

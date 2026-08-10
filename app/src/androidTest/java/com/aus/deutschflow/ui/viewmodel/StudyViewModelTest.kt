package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.service.TTSHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the rule that no award is ever lost.
 *
 * "Got it!" used to read the stats row, compute the new total in Kotlin and write it
 * back, all outside a transaction. Two taps in quick succession started two
 * coroutines that both read the same row before either wrote, so the second write
 * overwrote the first and one award vanished - silently, and only under the timing a
 * real user produces by tapping quickly, which is exactly the timing no manual test
 * reproduces reliably.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StudyViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var viewModel: StudyViewModel

    @Before
    fun setup() {
        // Unconfined runs each launch eagerly up to its first suspension point, which
        // is the database read. Firing the awards back to back therefore leaves them
        // all suspended mid-update at once - the interleaving being guarded against.
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        viewModel = StudyViewModel(
            database = database,
            vocabularyDao = database.vocabularyDao(),
            userStatsDao = database.userStatsDao(),
            preferenceManager = PreferenceManager(context),
            ttsHelper = TTSHelper(context)
        )
    }

    @After
    fun teardown() {
        database.close()
        Dispatchers.resetMain()
    }

    /** Waits on real time: the transactions run on Room's executor, not the scheduler. */
    private suspend fun awaitXp(expected: Int) {
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(TIMEOUT_MS) {
                database.userStatsDao().getUserStats().first { it?.xp == expected }
            }
        }
    }

    @Test
    fun everyAwardSurvivesConcurrentTaps() = runTest {
        repeat(AWARDS) { viewModel.rewardXP(POINTS) }

        awaitXp(AWARDS * POINTS)

        assertEquals(
            "all $AWARDS awards should be counted; a lost update means the " +
                "read-modify-write is no longer atomic",
            AWARDS * POINTS,
            database.userStatsDao().getUserStatsOnce()?.xp
        )
    }

    @Test
    fun concurrentTapsOnOneDayLeaveTheStreakAtOne() = runTest {
        repeat(AWARDS) { viewModel.rewardXP(POINTS) }

        awaitXp(AWARDS * POINTS)

        assertEquals(
            "awards within a single day are one day of the streak, however many there are",
            1,
            database.userStatsDao().getUserStatsOnce()?.streak
        )
    }

    @Test
    fun aSingleAwardStartsTheStreak() = runTest {
        viewModel.rewardXP(POINTS)

        awaitXp(POINTS)

        val stats = database.userStatsDao().getUserStatsOnce()
        assertEquals(POINTS, stats?.xp)
        assertEquals(1, stats?.streak)
    }

    private companion object {
        const val AWARDS = 10
        const val POINTS = 10
        const val TIMEOUT_MS = 5_000L
    }
}

package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.awaitCondition
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.service.TTSHelper
import kotlinx.coroutines.runBlocking
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
 * real user produces by tapping quickly.
 *
 * The awards are fired at the real main looper, exactly as the screen fires them,
 * and the test then waits. An earlier version swapped in a test dispatcher, which
 * cannot work in an instrumented test - see [awaitCondition].
 */
@RunWith(AndroidJUnit4::class)
class StudyViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var viewModel: StudyViewModel

    @Before
    fun setup() {
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
    }

    private suspend fun xp(): Int? = database.userStatsDao().getUserStatsOnce()?.xp

    @Test
    fun everyAwardSurvivesConcurrentTaps() = runBlocking {
        repeat(AWARDS) { viewModel.rewardXP(POINTS) }

        awaitCondition { xp() == AWARDS * POINTS }

        assertEquals(
            "all $AWARDS awards should be counted; a lost update means the " +
                "read-modify-write is no longer atomic",
            AWARDS * POINTS,
            xp()
        )
    }

    @Test
    fun concurrentTapsOnOneDayLeaveTheStreakAtOne() = runBlocking {
        repeat(AWARDS) { viewModel.rewardXP(POINTS) }

        awaitCondition { xp() == AWARDS * POINTS }

        assertEquals(
            "awards within a single day are one day of the streak, however many there are",
            1,
            database.userStatsDao().getUserStatsOnce()?.streak
        )
    }

    @Test
    fun aSingleAwardStartsTheStreak() = runBlocking {
        viewModel.rewardXP(POINTS)

        awaitCondition { xp() == POINTS }

        val stats = database.userStatsDao().getUserStatsOnce()
        assertEquals(POINTS, stats?.xp)
        assertEquals(1, stats?.streak)
    }

    private companion object {
        const val AWARDS = 10
        const val POINTS = 10
    }
}

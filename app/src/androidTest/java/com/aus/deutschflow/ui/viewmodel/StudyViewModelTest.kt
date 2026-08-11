package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.awaitCondition
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.KeystoreCipher
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.TTSHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two rules about XP: no award is ever lost, and no card is ever counted twice.
 *
 * The first was a race. "Got it!" read the stats row, computed the new total in
 * Kotlin and wrote it back, all outside a transaction, so two taps in quick
 * succession both read before either wrote and one award vanished.
 *
 * The second was arithmetic. nextCard() wraps with a modulo, so on a one-word
 * library the same card came back forever and XP could be minted by holding the
 * button down.
 *
 * The awards are fired at the real main looper, exactly as the screen fires them,
 * and the test then waits - see [awaitCondition] for why a test dispatcher cannot
 * be used here.
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
            preferenceManager = PreferenceManager(context, KeystoreCipher()),
            ttsHelper = TTSHelper(context)
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun xp(): Int? = database.userStatsDao().getUserStatsOnce()?.xp

    /** Seeds [count] words and starts a session over them. */
    private suspend fun startSessionOf(count: Int) {
        repeat(count) {
            database.vocabularyDao().insertVocabulary(
                VocabularyEntity(germanText = "wort$it", englishTranslation = "word$it")
            )
        }
        viewModel.startSession()
        awaitCondition { viewModel.studyList.value.size == count }
    }

    @Test
    fun everyDistinctCardIsCounted() = runBlocking {
        startSessionOf(CARDS)

        // Fired back to back, so the launches genuinely overlap: this is the
        // lost-update guard as much as it is a count.
        repeat(CARDS) {
            viewModel.rewardCurrentCard()
            viewModel.nextCard()
        }

        awaitCondition { xp() == CARDS * StudyViewModel.XP_PER_CARD }

        assertEquals(
            "all $CARDS cards should be counted; a lost update means the " +
                "read-modify-write is no longer atomic",
            CARDS * StudyViewModel.XP_PER_CARD,
            xp()
        )
    }

    @Test
    fun theSameCardCannotBeBankedTwice() = runBlocking {
        startSessionOf(CARDS)

        // Never advancing, so this is one card, ten times.
        repeat(10) { viewModel.rewardCurrentCard() }

        awaitCondition { xp() == StudyViewModel.XP_PER_CARD }

        assertEquals(
            "holding Got it! on one card should bank it once, not ten times",
            StudyViewModel.XP_PER_CARD,
            xp()
        )
    }

    @Test
    fun aNewSessionOffersTheCardsAgain() = runBlocking {
        startSessionOf(CARDS)
        viewModel.rewardCurrentCard()
        awaitCondition { xp() == StudyViewModel.XP_PER_CARD }

        // Re-entering the Study tab is a new session, and studying again is the
        // point of the app - only farming the same card inside one sitting is not.
        viewModel.startSession()
        awaitCondition { viewModel.studyList.value.size == CARDS }
        viewModel.rewardCurrentCard()

        awaitCondition { xp() == 2 * StudyViewModel.XP_PER_CARD }

        assertEquals(2 * StudyViewModel.XP_PER_CARD, xp())
    }

    @Test
    fun awardsOnOneDayLeaveTheStreakAtOne() = runBlocking {
        startSessionOf(CARDS)

        repeat(CARDS) {
            viewModel.rewardCurrentCard()
            viewModel.nextCard()
        }

        awaitCondition { xp() == CARDS * StudyViewModel.XP_PER_CARD }

        assertEquals(
            "awards within a single day are one day of the streak, however many there are",
            1,
            database.userStatsDao().getUserStatsOnce()?.streak
        )
    }

    @Test
    fun anEmptyLibraryBanksNothing() = runBlocking {
        viewModel.startSession()
        awaitCondition { viewModel.hasLoaded.value }

        viewModel.rewardCurrentCard()

        // No card on screen, so nothing to bank and nothing to crash on.
        awaitCondition(timeoutMs = 1_000) { xp() != null }
        assertEquals(null, xp())
    }

    private companion object {
        const val CARDS = 10
    }
}

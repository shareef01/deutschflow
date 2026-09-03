package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.awaitCondition
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.TestPreferencesRule
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.ReviewQuality
import com.aus.deutschflow.service.SRSEngine
import com.aus.deutschflow.service.TTSHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * What a review does, end to end: it banks XP once, it schedules the card, and it
 * files the day's activity.
 *
 * XP was a race once. "Got it!" read the stats row, computed the new total in Kotlin
 * and wrote it back, all outside a transaction, so two taps in quick succession both
 * read before either wrote and one award vanished. `rewardCurrentCard` now does the
 * read-modify-write inside `database.withTransaction`, and [everyAnsweredCardIsBanked]
 * is what would notice if that came apart.
 *
 * The old once-per-session guard (`awardedCardIds`) is gone, and does not need
 * replacing: a card answered at GOOD or better leaves the queue, so there is nothing
 * left on screen to bank a second time. [aCardLeavesTheSessionOnceItIsAnswered] is
 * the test that holds that line.
 *
 * The awards are fired at the real main looper, exactly as the screen fires them, and
 * the test then waits - see [awaitCondition] for why a test dispatcher cannot be used
 * here.
 */
@RunWith(AndroidJUnit4::class)
class StudyViewModelTest {

    @get:Rule
    val store = TestPreferencesRule("study-viewmodel-test")

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
            activityDao = database.activityDao(),
            preferenceManager = store.preferences,
            ttsHelper = TTSHelper(context),
            srsEngine = SRSEngine()
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

    /**
     * Answers [times] cards well, waiting for each award to land *and* for the
     * ViewModel to be ready for the next one.
     *
     * Sequential on purpose: `submitReview` reads the card at the current index when
     * it is called and writes the queue back from a coroutine, so firing them without
     * waiting would review one card several times over rather than several cards once.
     *
     * Two things this used to get wrong, which together made the test fail on a
     * loaded CI emulator roughly two runs in five:
     *
     * - [awaitCondition] returns false on timeout and the result was dropped, so a
     *   slow award did not fail here. The loop carried on and the shortfall surfaced
     *   as a baffling total in whichever assertion ran last.
     * - Waiting on the XP alone is not enough. `submitReview` clears `isSubmitting`
     *   in a `finally` that runs *after* the transaction commits, so the award is
     *   visible for a moment while the re-entry guard is still up - and a review
     *   fired in that window is refused outright, costing that card its ten points.
     *   That is the guard working as designed; the test simply has to respect it.
     */
    private suspend fun answerWell(times: Int) {
        repeat(times) { n ->
            viewModel.submitReview(ReviewQuality.GOOD)
            assertTrue(
                "card ${n + 1} of $times was never banked: its award did not land, or " +
                    "the ViewModel was still submitting when the next review came due",
                awaitCondition {
                    xp() == (n + 1) * StudyViewModel.XP_PER_CARD && !viewModel.isSubmitting.value
                }
            )
        }
    }

    @Test
    fun everyAnsweredCardIsBanked() = runBlocking {
        startSessionOf(CARDS)

        answerWell(CARDS)

        assertEquals(
            "all $CARDS cards should be counted; a shortfall means a review was " +
                "refused or an award was lost",
            CARDS * StudyViewModel.XP_PER_CARD,
            xp()
        )
        assertTrue("the deck should be spent", viewModel.studyList.value.isEmpty())
    }

    @Test
    fun aCardLeavesTheSessionOnceItIsAnswered() = runBlocking {
        startSessionOf(1)

        answerWell(1)

        // Nothing on screen to bank again - the queue is what enforces once-per-card
        // now, so holding "Got it!" down cannot mint XP.
        repeat(9) { viewModel.submitReview(ReviewQuality.GOOD) }
        awaitCondition(timeoutMs = 1_000) { viewModel.studyList.value.isEmpty() }

        assertEquals(
            "answering one card should bank it once, not ten times",
            StudyViewModel.XP_PER_CARD,
            xp()
        )
    }

    /**
     * Again is the one grade that keeps the card. It banks nothing, and the word stays
     * due, so the session can come back to it before the sitting is over.
     */
    @Test
    fun againKeepsTheCardAndBanksNothing() = runBlocking {
        startSessionOf(2)

        val card = viewModel.studyList.value.first()

        viewModel.submitReview(ReviewQuality.AGAIN)
        // The ease penalty, not the review count: a seeded card already has
        // reviewCount 0, so waiting on that would be true before the write landed
        // and would let this test pass without the code doing anything.
        awaitCondition {
            database.vocabularyDao().findByGermanText(card.germanText)?.easeFactor == 2.3f
        }

        val written = database.vocabularyDao().findByGermanText(card.germanText)
        assertEquals("Again costs ease", 2.3f, written?.easeFactor ?: 0f, 0.0001f)
        assertEquals("and leaves the card due now", 0L, written?.nextReview)
        assertEquals("Again is not a success, so nothing is banked", null, xp())
        assertEquals("the card goes to the back, it does not leave", 2, viewModel.studyList.value.size)
        assertEquals(
            "with the failed card moved to the end",
            card.germanText,
            viewModel.studyList.value.last().germanText
        )
    }

    /**
     * The point of the scheduler: a word answered well is not offered again the same
     * day. Before the SRS this deliberately went the other way - re-entering the tab
     * re-offered everything - so the change of behaviour is worth pinning down.
     */
    @Test
    fun aCardAnsweredWellIsNotDueAgainToday() = runBlocking {
        startSessionOf(1)
        answerWell(1)

        viewModel.startSession()
        awaitCondition { viewModel.hasLoaded.value }

        assertTrue(
            "a card scheduled for tomorrow should not be in today's session",
            viewModel.studyList.value.isEmpty()
        )
        assertEquals(
            "and it should still be banked exactly once",
            StudyViewModel.XP_PER_CARD,
            xp()
        )
    }

    @Test
    fun awardsOnOneDayLeaveTheStreakAtOne() = runBlocking {
        startSessionOf(CARDS)

        answerWell(CARDS)

        assertEquals(
            "awards within a single day are one day of the streak, however many there are",
            1,
            database.userStatsDao().getUserStatsOnce()?.streak
        )
    }

    /** The same day's awards accumulate into one heatmap square, rather than replacing it. */
    @Test
    fun theDaysActivityAccumulatesInOneRow() = runBlocking {
        startSessionOf(3)

        answerWell(3)

        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        // "0000-01-01" is simply a bound low enough to mean "every row".
        val log = database.activityDao().getActivitySince("0000-01-01").first()

        assertEquals("one row per day", 1, log.size)
        assertEquals(today, log.first().date)
        assertEquals(3 * StudyViewModel.XP_PER_CARD, log.first().xpGained)
    }

    @Test
    fun anEmptyLibraryBanksNothing() = runBlocking {
        viewModel.startSession()
        awaitCondition { viewModel.hasLoaded.value }

        viewModel.submitReview(ReviewQuality.GOOD)

        // No card on screen, so nothing to bank and nothing to crash on.
        awaitCondition(timeoutMs = 1_000) { xp() != null }
        assertEquals(null, xp())
    }

    private companion object {
        const val CARDS = 10
    }
}

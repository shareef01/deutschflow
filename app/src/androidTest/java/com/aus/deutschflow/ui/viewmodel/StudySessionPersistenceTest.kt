package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.TestPreferencesRule
import com.aus.deutschflow.awaitCondition
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.ReviewQuality
import com.aus.deutschflow.service.SRSEngine
import com.aus.deutschflow.service.TTSHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A sitting survives the user leaving the Flashcards tab and coming back.
 *
 * StudySessionContent leaves composition on every tab switch, and its entry
 * effect used to call `startSession()` unconditionally - so a glance at the
 * Dashboard reshuffled the deck, reset the current card and zeroed the
 * session's reviewed count mid-sitting. The screen now calls
 * [StudyViewModel.ensureSessionStarted], which loads only when no session is
 * in progress; [returningToTheTabDoesNotDisturbTheSession] is the test that
 * would notice the old behaviour coming back.
 *
 * The first entry must still load exactly as before - [firstEntryLoadsTheSession]
 * pins that down - and a deliberate "drill again" from the completion screen
 * goes through [StudyViewModel.restartSession], which must keep reloading
 * unconditionally: [drillAgainStillStartsAFreshSitting].
 */
@RunWith(AndroidJUnit4::class)
class StudySessionPersistenceTest {

    @get:Rule
    val store = TestPreferencesRule("study-session-persistence-test")

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

    /** Seeds [count] words without starting anything. */
    /**
     * Submits a grade the way a learner can: the answer has to be showing first.
     *
     * `submitReview` refuses a grade on an unrevealed card, because "Again" on a
     * card nobody has read resets its interval and review count — scheduling an
     * unseen word as one just failed. These tests drive the ViewModel directly, so
     * they have to perform the reveal a tap on the card would perform.
     */
    private fun answerRevealed(quality: ReviewQuality) {
        viewModel.flipCard()
        viewModel.submitReview(quality)
    }

    private suspend fun seedWords(count: Int) {
        repeat(count) {
            database.vocabularyDao().insertVocabulary(
                VocabularyEntity(germanText = "wort$it", englishTranslation = "word$it")
            )
        }
    }

    @Test
    fun firstEntryLoadsTheSession() = runBlocking {
        seedWords(3)

        viewModel.ensureSessionStarted()

        assertTrue(
            "the first entry should load the deck",
            awaitCondition { viewModel.hasLoaded.value && viewModel.studyList.value.size == 3 }
        )
        assertEquals(0, viewModel.sessionReviewedCount.value)
    }

    @Test
    fun returningToTheTabDoesNotDisturbTheSession() = runBlocking {
        seedWords(3)
        viewModel.ensureSessionStarted()
        assertTrue(awaitCondition { viewModel.studyList.value.size == 3 })

        // Answer one card so there is a reviewed count worth preserving, then
        // build the mid-sitting state a reload would destroy: a position past
        // the first card and a flipped card.
        answerRevealed(ReviewQuality.GOOD)
        assertTrue(
            "the review never landed",
            awaitCondition { viewModel.sessionReviewedCount.value == 1 && !viewModel.isSubmitting.value }
        )
        viewModel.skipCard()
        viewModel.flipCard()
        val deck = viewModel.studyList.value
        val index = viewModel.currentIndex.value
        assertTrue("the test needs a position and a flip worth keeping", index == 1 && viewModel.isFlipped.value)

        // The tab switch: StudySessionContent leaves composition and its entry
        // effect fires again on return.
        viewModel.ensureSessionStarted()

        // A reload would reshuffle and re-size the deck from a coroutine; give it
        // every chance to land before declaring the sitting untouched.
        kotlinx.coroutines.delay(500)
        assertEquals("the deck must not be reshuffled or reloaded", deck, viewModel.studyList.value)
        assertEquals("the reviewed count must not be zeroed", 1, viewModel.sessionReviewedCount.value)
        assertEquals("the position in the deck must not reset", index, viewModel.currentIndex.value)
        assertTrue("the flip must not reset", viewModel.isFlipped.value)
        assertEquals("three cards seeded, one answered", 2, viewModel.studyList.value.size)
    }

    @Test
    fun ensureSessionStartedTwiceBeforeTheLoadLandsLoadsOnce() = runBlocking {
        seedWords(2)

        viewModel.ensureSessionStarted()
        viewModel.ensureSessionStarted()

        assertTrue(awaitCondition { viewModel.hasLoaded.value })
        assertEquals("one load, one deck", 2, viewModel.studyList.value.size)
    }

    @Test
    fun drillAgainStillStartsAFreshSitting() = runBlocking {
        seedWords(2)
        viewModel.ensureSessionStarted()
        assertTrue(awaitCondition { viewModel.studyList.value.size == 2 })
        answerRevealed(ReviewQuality.GOOD)
        assertTrue(
            awaitCondition { viewModel.sessionReviewedCount.value == 1 && !viewModel.isSubmitting.value }
        )

        viewModel.restartSession()

        assertTrue(
            "drill again should re-offer the whole library",
            awaitCondition { viewModel.studyList.value.size == 2 && viewModel.sessionReviewedCount.value == 0 }
        )
        assertTrue("a re-drill is extra practice by definition", viewModel.isExtraPractice.value)
    }

    @Test
    fun tabSwitchDuringDrillAgainDoesNotReshuffle() = runBlocking {
        seedWords(2)
        viewModel.ensureSessionStarted()
        assertTrue(awaitCondition { viewModel.studyList.value.size == 2 })
        // One at a time: submitReview refuses re-entry while a save is in flight,
        // so a second answer fired before the first lands is dropped by design.
        answerRevealed(ReviewQuality.GOOD)
        assertTrue(
            awaitCondition { viewModel.sessionReviewedCount.value == 1 && !viewModel.isSubmitting.value }
        )
        answerRevealed(ReviewQuality.GOOD)
        assertTrue(
            "the sitting should be over",
            awaitCondition { viewModel.studyList.value.isEmpty() && !viewModel.isSubmitting.value }
        )

        // "Drill again" from the completion screen.
        viewModel.restartSession()
        assertTrue(awaitCondition { viewModel.studyList.value.size == 2 })
        answerRevealed(ReviewQuality.GOOD)
        assertTrue(
            "the drill-again review never landed",
            awaitCondition { viewModel.sessionReviewedCount.value == 1 && !viewModel.isSubmitting.value }
        )
        val deck = viewModel.studyList.value

        // The tab switch: completion re-armed the kickoff guard, and only an
        // armed restartSession keeps this entry from reloading mid-sitting.
        viewModel.ensureSessionStarted()

        kotlinx.coroutines.delay(500)
        assertEquals("the drill-again deck must not be reshuffled", deck, viewModel.studyList.value)
        assertEquals("the drill-again progress must not be zeroed", 1, viewModel.sessionReviewedCount.value)
    }

    @Test
    fun completingTheDeckReArmsTheNextTabEntry() = runBlocking {
        seedWords(1)
        viewModel.ensureSessionStarted()
        assertTrue(awaitCondition { viewModel.studyList.value.size == 1 })

        // Answer the only card: the sitting ends on the completion screen, and
        // the kickoff guard re-arms so the next tab entry loads afresh instead
        // of resurrecting that screen for the rest of the process's lifetime.
        answerRevealed(ReviewQuality.GOOD)
        assertTrue(
            "the review never landed",
            awaitCondition { viewModel.studyList.value.isEmpty() && !viewModel.isSubmitting.value }
        )

        viewModel.ensureSessionStarted()

        assertTrue(
            "re-entry after completion should load a fresh deck",
            awaitCondition {
                viewModel.studyList.value.size == 1 && viewModel.sessionReviewedCount.value == 0
            }
        )
    }

    @Test
    fun aDeckLoadedFromAnEmptyLibraryReloadsOnceTheLibraryFills() = runBlocking {
        // First entry with nothing in the library: an empty deck, "all caught up".
        viewModel.ensureSessionStarted()
        assertTrue(awaitCondition { viewModel.hasLoaded.value })
        assertTrue(viewModel.studyList.value.isEmpty())

        // A backup import (or a word added in the Library) while the user is away.
        seedWords(3)

        // Returning to Study: the empty deck was no session in progress, so it reloads.
        viewModel.ensureSessionStarted()

        assertTrue(
            "the deck should pick up the words added since the empty load",
            awaitCondition { viewModel.studyList.value.size == 3 && !viewModel.isExtraPractice.value }
        )
    }
}

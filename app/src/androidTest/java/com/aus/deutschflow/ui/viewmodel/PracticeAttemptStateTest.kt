package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.TestPreferencesRule
import com.aus.deutschflow.awaitCondition
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.TTSHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An attempt is described by three things - the word scores, the verdict, and the
 * transcript of what was said - and moving on to the next sentence has to forget all
 * three of them.
 *
 * Two live on this ViewModel and the third lives on the recogniser, and "Next" cleared
 * only the two it owned. The result card renders the transcript whenever it is not
 * empty, so the new sentence arrived above the words spoken for the previous one,
 * presented as what the user had just said - a wrong answer attributed to a sentence
 * they had not attempted yet.
 *
 * The scoring itself is a pure function and is covered by the JVM suite. This is about
 * the state that outlives a single attempt, which is the half that needs a real
 * recogniser, a real database and the real main looper - see [awaitCondition] for why
 * a test dispatcher cannot stand in for the last of those.
 */
@RunWith(AndroidJUnit4::class)
class PracticeAttemptStateTest {

    @get:Rule
    val store = TestPreferencesRule("practice-attempt-state-test")

    private lateinit var database: AppDatabase
    private lateinit var recognizer: SpeechRecognizerHelper
    private lateinit var viewModel: PracticeViewModel

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Seeded before the ViewModel exists, so its initial load has something to
        // find. One word makes "pick a random entry" a known answer, and the target
        // moving off its built-in default is how the test knows that load has
        // finished.
        database.vocabularyDao().insertVocabulary(
            VocabularyEntity(
                germanText = "Hund",
                englishTranslation = "dog",
                exampleSentence = TARGET
            )
        )

        // Held by the test as well as by the ViewModel: it owns the transcript, so it
        // is both how an utterance is delivered and where the missing clear lived.
        recognizer = SpeechRecognizerHelper(context)

        viewModel = PracticeViewModel(
            speechRecognizerHelper = recognizer,
            vocabularyDao = database.vocabularyDao(),
            preferenceManager = store.preferences,
            ttsHelper = TTSHelper(context)
        )

        check(awaitCondition { viewModel.targetSentence.first() == TARGET }) {
            "the seeded sentence should be loaded before the test begins"
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    /** Speaks [SPOKEN] at the target and waits for it to be scored. */
    private suspend fun anAttemptIsMade() {
        assertTrue(
            "the utterance should reach the scorer",
            // The results flow has no replay, so a delivery made before the
            // ViewModel's collector has reached the main looper would be dropped
            // rather than queued. Delivering until it lands beats racing it; a repeat
            // just re-scores the same words.
            awaitCondition {
                recognizer.deliverUtterance(SPOKEN)
                viewModel.wordResults.first().isNotEmpty()
            }
        )
    }

    /**
     * The premise. If an attempt does not leave all three pieces behind, the test
     * below is not proving anything.
     */
    @Test
    fun anAttemptLeavesScoresAVerdictAndATranscript() = runBlocking {
        anAttemptIsMade()

        assertEquals(SPOKEN, viewModel.finalText.first())
        assertTrue(viewModel.wordResults.first().isNotEmpty())
        assertNotEquals(PracticeFeedback.NONE, viewModel.feedback.first())
    }

    /**
     * The regression. Against the version that cleared only what it owned, the
     * transcript assertion fails and the two after it pass.
     */
    @Test
    fun movingToTheNextSentenceForgetsTheTranscriptToo() = runBlocking {
        anAttemptIsMade()

        viewModel.nextSentence()

        assertTrue(
            "the transcript should be cleared along with the rest of the attempt",
            awaitCondition { viewModel.finalText.first().isEmpty() }
        )
        assertEquals(
            "the previous attempt's words must not survive under the new sentence",
            "",
            viewModel.finalText.first()
        )
        assertTrue(
            "the word scores should go with it",
            viewModel.wordResults.first().isEmpty()
        )
        assertEquals(
            "and so should the verdict",
            PracticeFeedback.NONE,
            viewModel.feedback.first()
        )
    }

    private companion object {
        /** The seeded entry's example, which is what Practice asks the user to say. */
        const val TARGET = "Der Hund schläft."

        /** The same sentence spoken back, so the attempt scores as a real one. */
        const val SPOKEN = "Der Hund schläft"
    }
}

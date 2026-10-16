package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.awaitCondition
import com.aus.deutschflow.TestPreferencesRule
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.AIResult
import com.aus.deutschflow.service.GrammarNote
import com.aus.deutschflow.service.GroqHelper
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.service.VocabularyProcessor
import com.aus.deutschflow.service.WordDetails
import com.aus.deutschflow.service.WordDetailsResult
import com.aus.deutschflow.ui.widget.WidgetUpdater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins the rule that a failed translation must never reach the translation field.
 *
 * It matters because the Save button writes that field straight into the vocabulary
 * table: when a failure message lived there, "Translation failed" was storable as
 * the English meaning of a German sentence.
 *
 * No mocking and no network. An empty API key makes GroqHelper short-circuit to
 * AIResult.Failure before it ever builds a request, which is the same path a user
 * with no key takes - and the only place the wording of that failure can be checked,
 * now that it comes from resources and needs a Context.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptViewModelTest {

    /** This test's own store, so clearing the key cannot reach the user's. */
    @get:Rule
    val store = TestPreferencesRule("transcript-viewmodel-test")

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var viewModel: TranscriptViewModel

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Explicit rather than assumed: an empty key is what puts GroqHelper on the
        // failure path this whole class is about.
        store.preferences.saveApiKey("")

        viewModel = TranscriptViewModel(
            // Constructed but never started: nothing in this test touches the mic.
            speechRecognizerHelper = SpeechRecognizerHelper(context),
            vocabularyProcessor = VocabularyProcessor(GroqHelper(context)),
            vocabularyDao = database.vocabularyDao(),
            transcriptDao = database.transcriptDao(),
            preferenceManager = store.preferences,
            widgetUpdater = WidgetUpdater(context),
            ttsHelper = TTSHelper(context)
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun aFailedTranslationNeverReachesTheTranslationField() = runBlocking {
        viewModel.handleUtterance("Ich lerne Deutsch")

        assertEquals("", viewModel.translation.first())
        assertTrue(viewModel.suggestedWords.first().isEmpty())
    }

    @Test
    fun aFailedTranslationIsReportedToTheUser() = runBlocking {
        viewModel.handleUtterance("Ich lerne Deutsch")

        val error = viewModel.aiError.first()
        assertNotNull("the failure should be surfaced, not swallowed", error)
        assertTrue(
            "the message should say what to do about it, but was: $error",
            error!!.contains("API key", ignoreCase = true)
        )
    }

    @Test
    fun theTranscriptIsStoredEvenWhenTheTranslationFails() = runBlocking {
        viewModel.handleUtterance("Ich lerne Deutsch")

        val history = database.transcriptDao().getAllTranscripts().first()
        assertEquals(1, history.size)
        assertEquals("Ich lerne Deutsch", history.first().fullText)
    }

    /**
     * Two chips tapped in quick succession must leave the *second* word's anatomy in
     * the sheet, whichever request answers first.
     *
     * The chips stay tappable while a request is out. Before the interrogation was
     * cancelled on re-entry, both ran, and whichever answered last won: releasing the
     * first word's answer after the second's overwrote the sheet with a word the user
     * had not tapped - and the Save button then filed that one. This releases them in
     * exactly that order, so it fails against the unguarded version.
     */
    @Test
    fun aSupersededInterrogationNeverWinsTheSheet() = runBlocking {
        val scripted = ScriptedProcessor(context)
        val raced = viewModelWith(scripted)

        raced.interrogateWord("Hund")
        // Both requests are open before either is allowed to answer, which is the state
        // a fast second tap produces and the one the cancellation exists for.
        assertTrue(
            "the first interrogation should have reached the processor",
            awaitCondition { scripted.isWaitingOn("Hund") }
        )
        raced.interrogateWord("laufen")
        assertTrue(
            "the second interrogation should have reached the processor",
            awaitCondition { scripted.isWaitingOn("laufen") }
        )

        // The word the user actually tapped last answers first.
        scripted.answer("laufen")
        assertTrue(
            "the second word's anatomy should reach the sheet",
            awaitCondition { raced.wordDetails.first()?.word == "laufen" }
        )

        // Now let the superseded one through. It is cancelled, so this is a no-op -
        // and the assertion is that nothing changes.
        scripted.answer("Hund")
        assertFalse(
            "a superseded interrogation must never replace the sheet",
            awaitCondition(timeoutMs = 1_000) { raced.wordDetails.first()?.word == "Hund" }
        )
        assertEquals("laufen", raced.wordDetails.first()?.word)
        // And the spinner belongs to the live request, not the abandoned one.
        assertNull(raced.interrogatingWord.first())
    }

    /**
     * A failure that has already been superseded is not the snackbar's to clear.
     *
     * The screen shows a failure, which suspends for seconds, and clears it afterwards.
     * A chip tapped inside that window starts a fresh interrogation, and the blanket
     * dismiss that used to run there wiped the new word's answer as it landed.
     */
    @Test
    fun clearingAStaleFailureLeavesANewerOneAlone() = runBlocking {
        val scripted = ScriptedProcessor(context)
        val raced = viewModelWith(scripted)

        raced.interrogateWord("Hund")
        awaitCondition { scripted.isWaitingOn("Hund") }
        scripted.fail("Hund", "could not read the answer")
        assertTrue(
            "the failure should be surfaced",
            awaitCondition { raced.wordDetailError.first() != null }
        )
        val shown = raced.wordDetailError.first()!!

        // The user taps another chip while the snackbar is still up, and it fails too.
        raced.interrogateWord("laufen")
        awaitCondition { scripted.isWaitingOn("laufen") }
        scripted.fail("laufen", "the network went away")
        assertTrue(awaitCondition { raced.wordDetailError.first() == "the network went away" })

        // The snackbar for the *first* failure now finishes and clears what it showed.
        raced.dismissWordDetailError(shown)

        assertEquals(
            "the newer failure should survive the older one being dismissed",
            "the network went away",
            raced.wordDetailError.first()
        )
    }

    /** A TranscriptViewModel over [processor], sharing this test's database and store. */
    private fun viewModelWith(processor: VocabularyProcessor) = TranscriptViewModel(
        speechRecognizerHelper = SpeechRecognizerHelper(context),
        vocabularyProcessor = processor,
        vocabularyDao = database.vocabularyDao(),
        transcriptDao = database.transcriptDao(),
        preferenceManager = store.preferences,
        widgetUpdater = WidgetUpdater(context),
        ttsHelper = TTSHelper(context)
    )

    /**
     * A processor that holds every interrogation open until the test releases it, by
     * name.
     *
     * The real client cannot stand in here: with no API key it answers before it
     * suspends, and with one it answers when the network does. Neither lets a test keep
     * two requests in flight and choose the order they come back in, which is the only
     * thing the cancellation is about.
     */
    private class ScriptedProcessor(context: Context) : VocabularyProcessor(GroqHelper(context)) {

        private val gates = ConcurrentHashMap<String, CompletableDeferred<WordDetailsResult>>()

        override suspend fun interrogateWord(word: String, apiKey: String, learnerLevel: String?): WordDetailsResult =
            gates.getOrPut(word) { CompletableDeferred() }.await()

        fun isWaitingOn(word: String): Boolean = gates[word]?.isCompleted == false

        /** Answers [word] as the model does for an inflected form: with [dictionaryForm]. */
        fun answer(word: String, dictionaryForm: String = word) {
            gates.getOrPut(word) { CompletableDeferred() }.complete(
                WordDetailsResult.Success(
                    WordDetails(
                        word = dictionaryForm,
                        article = "der",
                        plural = "",
                        conjugationOrInfinitive = "",
                        meaning = "a meaning for $word",
                        exampleSentence = ""
                    )
                )
            )
        }

        fun fail(word: String, message: String) {
            gates.getOrPut(word) { CompletableDeferred() }
                .complete(WordDetailsResult.Failure(message))
        }
    }

    /**
     * A failed translation leaves nothing of the previous sentence's analysis behind.
     * The grammar notes used to survive it - hidden only because the screen happens
     * not to draw grammar without a translation.
     */
    @Test
    fun aFailedTranslationClearsTheLastGrammarNotes() = runBlocking {
        var next: AIResult = AIResult.Success(
            translation = "I see the dog",
            keywords = listOf("Hund"),
            example = "",
            grammarNotes = listOf(GrammarNote("den Hund", "Akkusativ", "direct object"))
        )
        val processor = object : VocabularyProcessor(GroqHelper(context)) {
            override suspend fun processText(text: String, apiKey: String, learnerLevel: String?) = next
        }
        val scripted = TranscriptViewModel(
            speechRecognizerHelper = SpeechRecognizerHelper(context),
            vocabularyProcessor = processor,
            vocabularyDao = database.vocabularyDao(),
            transcriptDao = database.transcriptDao(),
            preferenceManager = store.preferences,
            widgetUpdater = WidgetUpdater(context),
            ttsHelper = TTSHelper(context)
        )

        scripted.handleUtterance("Ich sehe den Hund")
        assertEquals(1, scripted.grammarNotes.value.size)

        next = AIResult.Failure("offline")
        scripted.handleUtterance("Noch ein Satz")

        assertTrue("the old sentence's grammar must go", scripted.grammarNotes.value.isEmpty())
    }

    /** The screen's Save reports a completed write, once. */
    @Test
    fun aRequestedSaveReportsThatItLanded() = runBlocking {
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { viewModel.saveOutcomes.first() }

        viewModel.saveTranscript("Ich lerne Deutsch", "I am learning German")

        assertEquals(SaveOutcome.SAVED, withTimeout(5_000) { outcome.await() })
        assertEquals(1, database.vocabularyDao().getAllVocabulary().first().size)
    }

    /**
     * A write the database refuses is reported, not fatal. Save used to run in the
     * screen's own coroutine scope, which has no exception handler, so this exact
     * failure took the app down.
     */
    @Test
    fun aSaveTheDatabaseRefusesIsReportedNotThrown() = runBlocking {
        // A full disk, delivered where a real one would surface: from the DAO's write.
        val fullDisk = object : VocabularyDao by database.vocabularyDao() {
            override suspend fun save(entry: VocabularyEntity) {
                throw SQLiteFullException("database or disk is full")
            }
        }
        val failing = TranscriptViewModel(
            speechRecognizerHelper = SpeechRecognizerHelper(context),
            vocabularyProcessor = VocabularyProcessor(GroqHelper(context)),
            vocabularyDao = fullDisk,
            transcriptDao = database.transcriptDao(),
            preferenceManager = store.preferences,
            widgetUpdater = WidgetUpdater(context),
            ttsHelper = TTSHelper(context)
        )
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { failing.saveOutcomes.first() }

        failing.saveTranscript("Ich lerne Deutsch", "I am learning German")

        assertEquals(SaveOutcome.FAILED, withTimeout(5_000) { outcome.await() })
    }

    /**
     * The lookup answers with the dictionary form, and Save files that form. The tapped
     * word is kept beside it, so the sheet can say "dictionary form of ging" instead of
     * silently saving a word the user never tapped.
     */
    @Test
    fun theTappedWordIsKeptBesideItsDictionaryForm() = runBlocking {
        val scripted = ScriptedProcessor(context)
        val lookup = viewModelWith(scripted)

        lookup.interrogateWord("ging")
        assertTrue(awaitCondition { scripted.isWaitingOn("ging") })
        scripted.answer("ging", dictionaryForm = "gehen")

        assertTrue(awaitCondition { lookup.wordDetails.first()?.word == "gehen" })
        assertEquals("ging", lookup.wordDetailsFor.first())

        lookup.dismissWordDetails()
        assertNull("closing the sheet forgets the tapped word too", lookup.wordDetailsFor.first())
    }

    @Test
    fun savingIsRejectedWhenEitherSideIsBlank() = runBlocking {
        viewModel.saveToVocabulary("Ich lerne Deutsch", "")
        viewModel.saveToVocabulary("", "I am learning German")

        // Nothing should ever arrive, so give the launches a moment to prove it.
        awaitCondition(timeoutMs = 1_000) {
            database.vocabularyDao().getAllVocabulary().first().isNotEmpty()
        }

        assertTrue(
            "a half-empty entry should never reach the library",
            database.vocabularyDao().getAllVocabulary().first().isEmpty()
        )
    }
}

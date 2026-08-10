package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.service.GroqHelper
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.VocabularyProcessor
import com.aus.deutschflow.ui.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the rule that a failed translation must never reach the translation field.
 *
 * It matters because the Save button writes that field straight into the vocabulary
 * table: when a failure message lived there, "Translation failed: no response from
 * the model" was storable as the English meaning of a German sentence.
 *
 * No mocking and no network. An empty API key makes GroqHelper short-circuit to
 * AIResult.Failure before it ever builds a request, which is the same path a user
 * with no key takes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TranscriptViewModelTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var viewModel: TranscriptViewModel

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        preferenceManager = PreferenceManager(context)
        // The DataStore is real and shared with the app, so set the key explicitly
        // rather than assuming the device happens to have none.
        preferenceManager.saveApiKey("")

        viewModel = TranscriptViewModel(
            // Constructed but never started: nothing in this test touches the mic.
            speechRecognizerHelper = SpeechRecognizerHelper(context),
            vocabularyProcessor = VocabularyProcessor(GroqHelper(context)),
            vocabularyDao = database.vocabularyDao(),
            transcriptDao = database.transcriptDao(),
            preferenceManager = preferenceManager,
            widgetUpdater = WidgetUpdater(context)
        )
    }

    @After
    fun teardown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun aFailedTranslationNeverReachesTheTranslationField() = runTest {
        viewModel.handleUtterance("Ich lerne Deutsch")

        assertEquals("", viewModel.translation.first())
        assertTrue(viewModel.suggestedWords.first().isEmpty())
    }

    @Test
    fun aFailedTranslationIsReportedToTheUser() = runTest {
        viewModel.handleUtterance("Ich lerne Deutsch")

        val error = viewModel.aiError.first()
        assertNotNull("the failure should be surfaced, not swallowed", error)
        assertTrue(
            "the message should say what to do about it, but was: $error",
            error!!.contains("API key", ignoreCase = true)
        )
    }

    @Test
    fun theTranscriptIsStoredEvenWhenTheTranslationFails() = runTest {
        viewModel.handleUtterance("Ich lerne Deutsch")

        val history = database.transcriptDao().getAllTranscripts().first()
        assertEquals(1, history.size)
        assertEquals("Ich lerne Deutsch", history.first().fullText)
    }

    @Test
    fun savingIsRejectedWhenEitherSideIsBlank() = runTest {
        viewModel.saveToVocabulary("Ich lerne Deutsch", "")
        viewModel.saveToVocabulary("", "I am learning German")

        assertTrue(
            "a half-empty entry should never reach the library",
            database.vocabularyDao().getAllVocabulary().first().isEmpty()
        )
    }

    @Test
    fun theHistorySearchFiltersOnText() = runTest {
        viewModel.handleUtterance("Guten Morgen")
        viewModel.handleUtterance("Gute Nacht")

        viewModel.setHistoryQuery("morgen")

        val filtered = viewModel.transcriptHistory.first { it.size == 1 }
        assertEquals("Guten Morgen", filtered.first().fullText)
    }
}

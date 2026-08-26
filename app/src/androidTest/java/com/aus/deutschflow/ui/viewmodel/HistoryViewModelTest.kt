package com.aus.deutschflow.ui.viewmodel

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.awaitCondition
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.service.TTSHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The history list and its search, which used to live on TranscriptViewModel and so
 * dragged the microphone stack onto a screen that only reads a table.
 */
@RunWith(AndroidJUnit4::class)
class HistoryViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        viewModel = HistoryViewModel(
            transcriptDao = database.transcriptDao(),
            ttsHelper = TTSHelper(ApplicationProvider.getApplicationContext())
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun seed(vararg texts: String) {
        texts.forEach { database.transcriptDao().insertTranscript(TranscriptEntity(fullText = it)) }
    }

    @Test
    fun theSearchFiltersOnText() = runBlocking {
        seed("Guten Morgen", "Gute Nacht")

        viewModel.setQuery("morgen")

        // Case-insensitively, so a lowercase query still finds a capitalised word.
        val filtered = viewModel.transcripts.first { it.size == 1 }
        assertEquals("Guten Morgen", filtered.first().fullText)
    }

    @Test
    fun anEmptyQueryShowsEverything() = runBlocking {
        seed("Guten Morgen", "Gute Nacht")

        assertEquals(2, viewModel.transcripts.first { it.size == 2 }.size)
    }

    @Test
    fun deletingRemovesTheRow() = runBlocking {
        seed("Guten Morgen")

        val stored = database.transcriptDao().getAllTranscripts().first().first()
        viewModel.deleteTranscript(stored)

        awaitCondition { database.transcriptDao().getAllTranscripts().first().isEmpty() }
        assertTrue(database.transcriptDao().getAllTranscripts().first().isEmpty())
    }
}

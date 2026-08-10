package com.aus.deutschflow.ui.viewmodel

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.DailyWord
import com.aus.deutschflow.service.DailyWordNotification
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the destructive action, which is the one place in the app where being
 * wrong is unrecoverable.
 *
 * The dialog says "This will permanently delete your library, history, and
 * earnings." It once deleted transcripts only - row by row, from a single snapshot -
 * leaving the vocabulary and the XP/streak untouched. Nothing would have caught that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        // viewModelScope dispatches on Main; unconfined runs those launches eagerly
        // so the assertions do not race the coroutine.
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        viewModel = SettingsViewModel(
            database = database,
            vocabularyDao = database.vocabularyDao(),
            transcriptDao = database.transcriptDao(),
            userStatsDao = database.userStatsDao(),
            preferenceManager = PreferenceManager(context),
            dailyWordNotification = DailyWordNotification(
                context,
                DailyWord(database.vocabularyDao())
            ),
            widgetUpdater = WidgetUpdater(context)
        )
    }

    @After
    fun teardown() {
        database.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedEverything() {
        database.vocabularyDao().insertVocabulary(
            VocabularyEntity(germanText = "das Haus", englishTranslation = "the house")
        )
        database.vocabularyDao().insertVocabulary(
            VocabularyEntity(germanText = "lernen", englishTranslation = "to learn")
        )
        database.transcriptDao().insertTranscript(TranscriptEntity(fullText = "Guten Tag"))
        database.userStatsDao().insertOrUpdate(
            UserStatsEntity(xp = 250, streak = 7, lastActivityTimestamp = 1000)
        )
    }

    @Test
    fun clearAllProgressEmptiesTheLibraryTheHistoryAndTheStats() = runTest {
        seedEverything()

        // Guard the seed, so a failure below cannot be a vacuous pass.
        assertEquals(2, database.vocabularyDao().getAllVocabulary().first().size)
        assertEquals(1, database.transcriptDao().getAllTranscripts().first().size)
        assertNotNull(database.userStatsDao().getUserStats().first())

        viewModel.clearAllProgress()

        assertTrue(
            "the library should be empty",
            database.vocabularyDao().getAllVocabulary().first().isEmpty()
        )
        assertTrue(
            "the history should be empty",
            database.transcriptDao().getAllTranscripts().first().isEmpty()
        )
        assertNull(
            "the XP and streak should be gone",
            database.userStatsDao().getUserStats().first()
        )
    }

    @Test
    fun clearAllProgressTellsTheUserWhatItDid() = runTest {
        seedEverything()

        viewModel.clearAllProgress()

        assertEquals("Library, history and stats cleared.", viewModel.message.first())
    }

    @Test
    fun clearAllProgressOnAnEmptyDatabaseIsHarmless() = runTest {
        viewModel.clearAllProgress()

        assertTrue(database.vocabularyDao().getAllVocabulary().first().isEmpty())
        assertNull(database.userStatsDao().getUserStats().first())
    }

    @Test
    fun theStatCountsFollowTheDatabase() = runTest {
        seedEverything()

        assertEquals(2, viewModel.totalVocabulary.first { it == 2 })
        assertEquals(1, viewModel.totalTranscripts.first { it == 1 })
        assertEquals(250, viewModel.userStats.first { it != null }?.xp)
    }
}

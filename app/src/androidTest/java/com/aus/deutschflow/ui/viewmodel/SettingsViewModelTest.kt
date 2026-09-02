package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.R
import com.aus.deutschflow.awaitCondition
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.entities.RoleplayMessageEntity
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.DailyWord
import com.aus.deutschflow.TestPreferencesRule
import com.aus.deutschflow.service.DailyWordNotification
import com.aus.deutschflow.ui.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the destructive action, which is the one place in the app where being
 * wrong is unrecoverable.
 *
 * The dialog says "This will permanently delete your library, history, and
 * earnings." It once deleted transcripts only - row by row, from a single snapshot -
 * leaving the vocabulary and the XP/streak untouched. Nothing would have caught that.
 *
 * The wipe runs on the real main looper and the test waits for it; swapping in a
 * test dispatcher does not work in an instrumented test, see [awaitCondition].
 */
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {

    @get:Rule
    val store = TestPreferencesRule("settings-viewmodel-test")

    private lateinit var database: AppDatabase
    private lateinit var viewModel: SettingsViewModel


    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        viewModel = SettingsViewModel(
            database = database,
            vocabularyDao = database.vocabularyDao(),
            transcriptDao = database.transcriptDao(),
            userStatsDao = database.userStatsDao(),
            preferenceManager = store.preferences,
            dailyWordNotification = DailyWordNotification(
                context,
                DailyWord(database.vocabularyDao())
            ),
            activityDao = database.activityDao(),
            roleplayDao = database.roleplayDao(),
            widgetUpdater = WidgetUpdater(context)
        )
    }

    @After
    fun teardown() {
        database.close()
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
        database.roleplayDao().insert(
            RoleplayMessageEntity(
                position = 0,
                scenario = "Ordering at a Berlin Bakery",
                role = "assistant",
                content = "Guten Morgen! Was darf es sein?",
                translation = "Good morning! What would you like?"
            )
        )
    }

    private suspend fun vocabularyIsEmpty() =
        database.vocabularyDao().getAllVocabulary().first().isEmpty()

    @Test
    fun clearAllProgressEmptiesTheLibraryTheHistoryAndTheStats() = runBlocking {
        seedEverything()

        // Guard the seed, so a failure below cannot be a vacuous pass.
        assertEquals(2, database.vocabularyDao().getAllVocabulary().first().size)
        assertEquals(1, database.transcriptDao().getAllTranscripts().first().size)
        assertNotNull(database.userStatsDao().getUserStatsOnce())
        assertEquals(1, database.roleplayDao().getConversation().size)

        viewModel.clearAllProgress()
        awaitCondition { vocabularyIsEmpty() }

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
            database.userStatsDao().getUserStatsOnce()
        )
        assertTrue(
            "the saved roleplay is the user's speech too, and the dialog says all",
            database.roleplayDao().getConversation().isEmpty()
        )
    }

    @Test
    fun clearAllProgressTellsTheUserWhatItDid() = runBlocking {
        seedEverything()

        viewModel.clearAllProgress()
        awaitCondition { viewModel.message.value != null }

        // A resource id, not a sentence: the ViewModel no longer holds English.
        assertEquals(R.string.message_progress_cleared, viewModel.message.value)
    }

    @Test
    fun clearAllProgressOnAnEmptyDatabaseIsHarmless() = runBlocking {
        viewModel.clearAllProgress()
        awaitCondition { viewModel.message.value != null }

        assertTrue(database.vocabularyDao().getAllVocabulary().first().isEmpty())
        assertNull(database.userStatsDao().getUserStatsOnce())
    }

    @Test
    fun theStatCountsFollowTheDatabase() = runBlocking {
        seedEverything()

        assertEquals(2, viewModel.totalVocabulary.first { it == 2 })
        assertEquals(1, viewModel.totalTranscripts.first { it == 1 })
        assertEquals(250, viewModel.userStats.first { it != null }?.xp)
    }
}

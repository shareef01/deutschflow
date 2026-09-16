package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aus.deutschflow.TestPreferencesRule
import com.aus.deutschflow.awaitCondition
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.service.GroqHelper
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.service.VocabularyProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class RoleplayViewModelTest {
    @get:Rule val preferences = TestPreferencesRule("roleplay-completion-test")

    @Test
    fun automaticSpeechCompletionSendsOnceWithoutManualStop() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val inputs = CopyOnWriteArrayList<String>()
        val owner = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var recognizer: SpeechRecognizerHelper
        lateinit var viewModel: RoleplayViewModel
        var tts: TTSHelper? = null
        preferences.preferences.setAutoPlayEnabled(false)
        val processor = object : VocabularyProcessor(GroqHelper(context)) {
            override suspend fun continueRoleplay(userInput: String, history: List<Pair<String, String>>,
                scenario: String, apiKey: String, learnerLevel: String?): GroqHelper.RoleplayResult {
                inputs.add(userInput)
                return GroqHelper.RoleplayResult.Success("Guten Tag", "Hello")
            }
        }
        try {
            instrumentation.runOnMainSync {
                recognizer = SpeechRecognizerHelper(context)
                tts = TTSHelper(context)
                viewModel = RoleplayViewModel(recognizer, processor, requireNotNull(tts), preferences.preferences, database.roleplayDao())
                owner.put("roleplay", viewModel)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { recognizer.deliverUtterance("Ein Brot bitte") }
            assertTrue(awaitCondition { viewModel.messages.value.size == 2 && !viewModel.isProcessing.value })
            assertEquals(listOf("Ein Brot bitte"), inputs.toList())
            // A late Stop must not resend the retained finalText from the previous utterance.
            instrumentation.runOnMainSync { viewModel.stopListeningAndSend() }
            delay(100)
            assertEquals(listOf("Ein Brot bitte"), inputs.toList())
            assertEquals(2, database.roleplayDao().getConversation().size)
        } finally {
            instrumentation.runOnMainSync { owner.clear(); tts?.shutdown() }
            instrumentation.waitForIdleSync()
            database.close()
        }
    }
}

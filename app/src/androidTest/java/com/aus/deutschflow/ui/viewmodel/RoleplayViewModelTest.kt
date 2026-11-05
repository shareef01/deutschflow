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
import com.aus.deutschflow.data.model.RoleplayScenarioCatalog
import kotlinx.coroutines.CompletableDeferred
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
                viewModel = RoleplayViewModel(
                    context = context,
                    groqHelper = GroqHelper(context),
                    speechRecognizerHelper = recognizer,
                    preferenceManager = preferences.preferences,
                    ttsHelper = requireNotNull(tts),
                    vocabularyProcessor = processor,
                    roleplayDao = database.roleplayDao()
                )
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

    /**
     * Switching scene while the model is still answering must not let the old scene's
     * reply into the new one. It used to be appended, persisted under the new scenario
     * and spoken, while the new scene's opening line was swallowed by the in-flight
     * guard.
     */
    @Test
    fun switchingSceneMidTurnDropsTheOldReplyAndOpensTheNewScene() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val owner = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var viewModel: RoleplayViewModel
        var tts: TTSHelper? = null
        preferences.preferences.setAutoPlayEnabled(false)
        // The old scene's reply, held until the test lets it arrive - after the switch.
        val staleReply = CompletableDeferred<GroqHelper.RoleplayResult>()
        val processor = object : VocabularyProcessor(GroqHelper(context)) {
            override suspend fun startRoleplay(scenario: String, history: List<Pair<String, String>>,
                apiKey: String, learnerLevel: String?): GroqHelper.RoleplayResult =
                GroqHelper.RoleplayResult.Success("Willkommen: $scenario", "Welcome")

            override suspend fun continueRoleplay(userInput: String, history: List<Pair<String, String>>,
                scenario: String, apiKey: String, learnerLevel: String?): GroqHelper.RoleplayResult =
                staleReply.await()
        }
        val cafe = RoleplayScenarioCatalog.SCENARIOS.first { it.id == "cafe" }
        try {
            instrumentation.runOnMainSync {
                tts = TTSHelper(context)
                viewModel = RoleplayViewModel(
                    context = context,
                    groqHelper = GroqHelper(context),
                    speechRecognizerHelper = SpeechRecognizerHelper(context),
                    preferenceManager = preferences.preferences,
                    ttsHelper = requireNotNull(tts),
                    vocabularyProcessor = processor,
                    roleplayDao = database.roleplayDao()
                )
                owner.put("roleplay", viewModel)
                viewModel.startSession("bakery")
            }
            assertTrue(awaitCondition { viewModel.messages.value.size == 1 && !viewModel.isProcessing.value })

            instrumentation.runOnMainSync { viewModel.sendInput("Ein Brot bitte") }
            assertTrue(awaitCondition { viewModel.isProcessing.value && viewModel.messages.value.size == 2 })

            // The switch, while the bakery turn is still waiting on the model.
            instrumentation.runOnMainSync { viewModel.selectScenario(cafe) }
            assertTrue(
                "the new scene should open with its own greeting",
                awaitCondition {
                    viewModel.messages.value.size == 1 &&
                        viewModel.messages.value.first().content == "Willkommen: ${cafe.title}" &&
                        !viewModel.isProcessing.value
                }
            )

            // Now the bakery's answer arrives - too late to belong anywhere.
            staleReply.complete(GroqHelper.RoleplayResult.Success("STALE", "from the bakery"))
            delay(500)

            assertTrue(
                "the old scene's reply must not reach the new one",
                viewModel.messages.value.none { it.content == "STALE" }
            )
            val stored = database.roleplayDao().getConversation()
            assertTrue("nor be saved into it", stored.none { it.content == "STALE" })
            assertEquals(listOf("cafe"), stored.map { it.scenario }.distinct())
        } finally {
            instrumentation.runOnMainSync { owner.clear(); tts?.shutdown() }
            instrumentation.waitForIdleSync()
            database.close()
        }
    }
    @Test
    fun leavingMidReplyCancelsTheTurnAndRetryStillWorks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val owner = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val pendingReply = CompletableDeferred<GroqHelper.RoleplayResult>()
        var calls = 0
        var tts: TTSHelper? = null
        lateinit var viewModel: RoleplayViewModel
        preferences.preferences.setAutoPlayEnabled(true)
        val processor = object : VocabularyProcessor(GroqHelper(context)) {
            override suspend fun continueRoleplay(userInput: String, history: List<Pair<String, String>>,
                scenario: String, apiKey: String, learnerLevel: String?): GroqHelper.RoleplayResult {
                if (++calls == 1) {
                    started.complete(Unit)
                    try { return pendingReply.await() }
                    finally { cancelled.complete(Unit) }
                }
                return GroqHelper.RoleplayResult.Success("Recovered reply", "Recovered")
            }
        }
        try {
            instrumentation.runOnMainSync {
                tts = TTSHelper(context)
                viewModel = RoleplayViewModel(context, GroqHelper(context), SpeechRecognizerHelper(context),
                    preferences.preferences, requireNotNull(tts), processor, database.roleplayDao())
                owner.put("roleplay", viewModel)
                viewModel.sendInput("Ein Brot bitte")
            }
            assertTrue(awaitCondition { started.isCompleted })
            instrumentation.runOnMainSync { viewModel.onLeavingScreen() }
            assertTrue(awaitCondition { cancelled.isCompleted })
            assertTrue(!viewModel.isProcessing.value)
            assertEquals(context.getString(com.aus.deutschflow.R.string.ai_cancelled), viewModel.error.value)
            pendingReply.complete(GroqHelper.RoleplayResult.Success("STALE", "must not speak"))
            delay(100)
            assertEquals(listOf("Ein Brot bitte"), viewModel.messages.value.map { it.content })
            assertTrue(database.roleplayDao().getConversation().none { it.content == "STALE" })
            // The saved user turn remains available for the existing Retry action.
            preferences.preferences.setAutoPlayEnabled(false)
            instrumentation.runOnMainSync { viewModel.retry() }
            assertTrue(awaitCondition { viewModel.messages.value.lastOrNull()?.content == "Recovered reply" && !viewModel.isProcessing.value })
            assertEquals(2, calls)
        } finally {
            instrumentation.runOnMainSync { owner.clear(); tts?.shutdown() }
            instrumentation.waitForIdleSync()
            database.close()
        }
    }

}

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
}

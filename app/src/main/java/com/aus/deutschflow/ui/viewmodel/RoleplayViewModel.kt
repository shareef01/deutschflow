package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.service.GroqHelper
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.service.VocabularyProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class ChatMessage(
    val role: String, // "user" or "assistant"
    val content: String,
    val translation: String? = null
)

@HiltViewModel
class RoleplayViewModel @Inject constructor(
    private val speechRecognizerHelper: SpeechRecognizerHelper,
    private val vocabularyProcessor: VocabularyProcessor,
    private val ttsHelper: TTSHelper,
    private val preferenceManager: PreferenceManager
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    /** The last turn's failure, shown in the chat so a dead screen is never silent. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val isListening: StateFlow<Boolean> = speechRecognizerHelper.isListening
    val partialText: StateFlow<String> = speechRecognizerHelper.partialText
    val errorState: StateFlow<String?> = speechRecognizerHelper.errorState

    private var currentScenario = SCENARIO_BERLIN_BAKERY

    fun startSession(scenario: String) {
        currentScenario = scenario
        _messages.value = emptyList()
        _error.value = null
        // The model opens: an empty user turn is the cue for its greeting.
        sendInput("")
    }

    fun startListening() {
        _error.value = null
        // The recogniser's error outlives the turn that caused it, and this screen
        // renders it, so a stale one would greet the new attempt. The banner should
        // belong to the action the user just took.
        speechRecognizerHelper.dismissError()
        viewModelScope.launch {
            // The stored dialect, not the de-DE default. Transcript and Practice both
            // pass it; roleplay called the no-argument overload, so an Austrian or
            // Swiss user's setting silently did not apply on the one screen where
            // they speak the most.
            speechRecognizerHelper.startListening(preferenceManager.selectedDialect.first())
        }
    }

    /** Called when the screen leaves composition or the app is backgrounded. */
    fun cancelListening() {
        speechRecognizerHelper.cancel()
    }

    /**
     * Ends the utterance and sends whatever the engine delivers.
     *
     * The wait is bounded and the result is taken as an event. Awaiting a non-blank
     * [SpeechRecognizerHelper.finalText] instead hung forever when recognition failed
     * - the send button stuck mid-tap - or, worse, re-sent the previous turn, because
     * that field keeps the last utterance until a new session clears it.
     */
    fun stopListeningAndSend() {
        speechRecognizerHelper.stopListening()
        viewModelScope.launch {
            val text = withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
                speechRecognizerHelper.finalText.filter { it.isNotBlank() }.first()
            }
            if (text != null) {
                speechRecognizerHelper.clearTranscript()
                sendInput(text)
            }
        }
    }

    /** Re-sends the turn that failed, so a failed opening line is recoverable. */
    fun retry() {
        val last = _messages.value.lastOrNull()
        if (last?.role == "user") {
            _messages.value = _messages.value.dropLast(1)
            sendInput(last.content)
        } else {
            sendInput("")
        }
    }

    private fun sendInput(userInput: String) {
        // Set before the launch, not inside it: two taps in the same frame both
        // read the old value and both got through.
        if (_isProcessing.value) return
        _isProcessing.value = true
        _error.value = null

        // Trimmed, because every turn resends the whole conversation: a long
        // roleplay eventually exceeded the model's context window and surfaced as a
        // generic "Translation failed" with no way to tell what had gone wrong.
        val history = _messages.value
            .takeLast(MAX_HISTORY_TURNS)
            .map { it.role to it.content }

        if (userInput.isNotBlank()) {
            _messages.value += ChatMessage("user", userInput)
        }

        viewModelScope.launch {
            try {
                val apiKey = preferenceManager.apiKey.first()
                when (val result =
                    vocabularyProcessor.processRoleplay(userInput, history, currentScenario, apiKey)) {
                    is GroqHelper.RoleplayResult.Success -> {
                        _messages.value += ChatMessage(
                            "assistant",
                            result.aiResponse,
                            result.englishContext
                        )
                        if (preferenceManager.isAutoPlayEnabled.first()) {
                            ttsHelper.speak(result.aiResponse)
                        }
                    }
                    // Shown, not swallowed. A failed greeting used to leave the screen
                    // blank with no error and nothing that would ever try again.
                    is GroqHelper.RoleplayResult.Failure -> _error.value = result.message
                }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * Silences the engine when the screen goes away.
     *
     * TTSHelper is a @Singleton and was only ever torn down in
     * MainActivity.onDestroy behind `isFinishing`, which is false when the app is
     * merely backgrounded - so a word spoken here kept playing after the user
     * switched tabs or left the app. The microphone was already released on both
     * paths; the voice was not.
     */
    fun stopSpeaking() {
        ttsHelper.stop()
    }

    fun speak(text: String) {
        ttsHelper.speak(text)
    }

    override fun onCleared() {
        // No super call: ViewModel.onCleared is @EmptySuper, and the rest of the
        // app's ViewModels omit it for the same reason.
        speechRecognizerHelper.destroy()
    }

    companion object {
        /**
         * Sent to the model as scene-setting, not shown in the UI - the header
         * renders the localized resource for the same scenario. The screen's two
         * startSession call sites and this default all share the one constant.
         */
        const val SCENARIO_BERLIN_BAKERY = "Ordering at a Berlin Bakery"

        /**
         * Long enough for the engine to finish an utterance it already heard, short
         * enough that a failed recognition returns the button rather than keeping it.
         */
        private const val RECOGNITION_TIMEOUT_MS = 5_000L

        /**
         * How much of the conversation the model is shown.
         *
         * Enough for the scene to hold together, bounded so a long session cannot
         * grow the request until the context window rejects it. The system prompt is
         * always sent; this caps only the turns.
         */
        internal const val MAX_HISTORY_TURNS = 12
    }
}

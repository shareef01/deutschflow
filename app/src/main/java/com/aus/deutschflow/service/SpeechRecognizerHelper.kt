package com.aus.deutschflow.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

private const val TAG = "SpeechRecognizerHelper"

/**
 * Wraps [SpeechRecognizer], which must be driven from the main thread and answers
 * asynchronously through [RecognitionListener].
 *
 * Deliberately unscoped rather than a `@Singleton`: each ViewModel owns an instance
 * and destroys it in `onCleared`. A shared instance would deliver every utterance to
 * every collector, so a sentence spoken on the Practice screen would also be filed
 * as a transcript.
 */
class SpeechRecognizerHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _finalText = MutableStateFlow("")
    val finalText: StateFlow<String> = _finalText.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /** True between the end of speech and the arrival of the final result. */
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    /**
     * One emission per completed utterance.
     *
     * Callers must react to this rather than reading [finalText] after calling
     * [stopListening]: the engine has not answered at that point, so the state flow
     * still holds the previous session's text.
     */
    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val results: SharedFlow<String> = _results.asSharedFlow()

    fun startListening(languageTag: String = DEFAULT_LANGUAGE) {
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null

                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    _errorState.value = "Speech recognition isn't available on this device."
                    return@post
                }

                // Clear the previous session first, so a stale result can never be
                // mistaken for this one's.
                _partialText.value = ""
                _finalText.value = ""
                _errorState.value = null
                _isProcessing.value = false
                _isListening.value = true

                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer.setRecognitionListener(recognitionListener)
                recognizer.startListening(buildIntent(languageTag))
                speechRecognizer = recognizer
            } catch (e: Exception) {
                Log.e(TAG, "Could not start recognition", e)
                _errorState.value = "Couldn't start recording. Try again."
                _isListening.value = false
                _isProcessing.value = false
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            // The final result still arrives later, in onResults.
            if (_isListening.value) _isProcessing.value = true
            _isListening.value = false
            speechRecognizer?.stopListening()
        }
    }

    /**
     * Abandons the current utterance without delivering it.
     *
     * Distinct from [stopListening], which asks for a result: when the user walks
     * away from the screen or backgrounds the app mid-sentence, filing half a
     * sentence as a transcript is worse than filing nothing. The recognizer itself
     * is kept, so returning to the screen does not pay to rebuild it.
     */
    fun cancel() {
        mainHandler.post {
            speechRecognizer?.cancel()
            _isListening.value = false
            _isProcessing.value = false
            _partialText.value = ""
        }
    }

    /**
     * Reports a denied microphone permission through the same channel as every other
     * reason recording could not start.
     *
     * The screens used to ignore a denial entirely. Android stops showing the system
     * dialog after the second refusal, so from then on the app's primary control did
     * nothing at all and said nothing about why.
     */
    fun reportPermissionDenied() {
        _errorState.value = MICROPHONE_DENIED
        _isListening.value = false
        _isProcessing.value = false
    }

    fun destroy() {
        mainHandler.post {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            _isListening.value = false
            _isProcessing.value = false
        }
    }

    private fun buildIntent(languageTag: String) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _errorState.value = null
        }

        override fun onBeginningOfSpeech() {
            _partialText.value = ""
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Capture is over but the result is still in flight. Holding a distinct
            // processing state stops the UI offering "record" again mid-answer.
            _isListening.value = false
            _isProcessing.value = true
        }

        override fun onError(error: Int) {
            _errorState.value = messageFor(error)
            _isListening.value = false
            _isProcessing.value = false
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()

            _isListening.value = false
            _isProcessing.value = false

            if (text.isNotBlank()) {
                _finalText.value = text
                _results.tryEmit(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.let { _partialText.value = it }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Recognition content is never logged - these describe the failure to the user
     * and say what to do about it.
     */
    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO ->
            "Microphone unavailable. Close anything else using it and try again."
        SpeechRecognizer.ERROR_CLIENT ->
            "Recording couldn't start. Try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> MICROPHONE_DENIED
        SpeechRecognizer.ERROR_NETWORK ->
            "No connection. Speech recognition needs network access."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The network timed out. Try again."
        SpeechRecognizer.ERROR_NO_MATCH ->
            "Didn't catch that. Try speaking again, a little slower."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Still finishing the last recording. Try again in a moment."
        SpeechRecognizer.ERROR_SERVER ->
            "The speech service had a problem. Try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No speech detected."
        else ->
            "Speech recognition failed. Try again."
    }

    companion object {
        const val DEFAULT_LANGUAGE = "de-DE"

        /**
         * "Android's app settings", not "Settings": the app has a Settings tab of its
         * own, and the microphone toggle is not in it.
         */
        const val MICROPHONE_DENIED =
            "Microphone access is off. Turn it on for DeutschFlow in Android's app settings."
    }
}

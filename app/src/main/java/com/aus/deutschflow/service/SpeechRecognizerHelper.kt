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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SpeechRecognizerHelper"

@Singleton
class SpeechRecognizerHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var speechRecognizer: SpeechRecognizer? = null
    
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    private val _finalText = MutableStateFlow("")
    val finalText: StateFlow<String> = _finalText

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Operation Thread-Lock: Standardized Transcription Initialization
     */
    fun startListening() {
        // rule 1: Absolute Main-Thread Lockdown
        mainHandler.post {
            try {
                // rule 3: Aggressive Lifecycle Purge
                Log.d(TAG, "Executing aggressive purge of existing recognizer")
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null

                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    _errorState.value = "CRITICAL SYSTEM FAILURE: Speech Services Not Installed"
                    return@post
                }

                // rule 2: Sequential Listener Attachment
                Log.d(TAG, "Sequential Initialization: Create -> Attach -> Start")
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(createRecognitionListener())

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                Log.d(TAG, "Invoking startListening(de-DE) on Main Thread")
                speechRecognizer?.startListening(intent)
                
            } catch (e: Exception) {
                val errorMsg = "INTERNAL CLIENT ERROR: ${e.message}"
                _errorState.value = errorMsg
                Log.e(TAG, errorMsg)
            }
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Hardware Ready: Calibration complete")
            _isListening.value = true
            _errorState.value = null
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech Detected: Input session active")
            _partialText.value = ""
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech Ended: Closing session")
            _isListening.value = false
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO: Mic hardware failure"
                SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT: Code 5 - Thread/Lifecycle Botch"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_PERMISSIONS: Check Manifest"
                SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK: Connectivity lost"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH: No valid German found"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_BUSY: Parallel session active"
                SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER: Cloud processing failure"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_TIMEOUT: Silence detected"
                else -> "ERROR_UNKNOWN: Code $error"
            }
            Log.e(TAG, "Recognizer Threw: $message")
            _errorState.value = message
            _isListening.value = false
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            Log.d(TAG, "Final Result Captured: $matches")
            if (!matches.isNullOrEmpty()) {
                _finalText.value = matches[0]
            }
            _isListening.value = false
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d(TAG, "Partial: ${matches[0]}")
                _partialText.value = matches[0]
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun stopListening() {
        mainHandler.post {
            Log.d(TAG, "User Interrupted: Stopping engine")
            speechRecognizer?.stopListening()
            _isListening.value = false
        }
    }

    fun destroy() {
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }
}

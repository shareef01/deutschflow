package com.aus.deutschflow.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.aus.deutschflow.R
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

    /** The tag of the session in flight, so a failure can name the language it wanted. */
    private var currentLanguage = DEFAULT_LANGUAGE

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
     * Instantaneous input level, normalised to 0..1.
     *
     * Updated from [RecognitionListener.onRmsChanged]. Consumers must read it inside
     * a draw phase (a Canvas or graphicsLayer lambda) rather than collect it into
     * composition: the engine emits it many times a second, and a recomposition per
     * sample would be the one thing the waveform was built to avoid.
     */
    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

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
            currentLanguage = languageTag
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null

                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    _errorState.value = context.getString(R.string.speech_unavailable)
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
                _errorState.value = context.getString(R.string.speech_start_failed)
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
            _rmsLevel.value = 0f
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
        _errorState.value = context.getString(R.string.speech_error_permission)
        _isListening.value = false
        _isProcessing.value = false
    }

    /**
     * Drops a failure that is no longer the most recent thing to have gone wrong.
     *
     * [_errorState] otherwise survives until the next [startListening], which is a
     * problem wherever it is merged with another source: Practice shows the
     * recogniser's error in preference to the voice engine's, so a stale one hid
     * every later text-to-speech failure on that screen. Same role as
     * TTSHelper.dismissError.
     */
    fun dismissError() {
        _errorState.value = null
    }

    fun destroy() {
        mainHandler.post {
            teardownRecognizer()
            _isListening.value = false
            _isProcessing.value = false
            _rmsLevel.value = 0f
        }
    }

    /**
     * Tears the engine down so the next [startListening] builds a fresh instance.
     *
     * [SpeechRecognizer.ERROR_CLIENT] is the framework's way of saying the current
     * instance is in an unrecoverable state; keeping it would just fail again.
     */
    private fun teardownRecognizer() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /**
     * Clears a recoverable error after a beat, but only if nothing newer superseded
     * it. A busy recogniser or a silent utterance should not leave a banner up that
     * the user has to read past on their next, successful attempt.
     */
    private fun scheduleErrorReset(message: String) {
        mainHandler.postDelayed({
            if (_errorState.value == message) _errorState.value = null
        }, ERROR_RESET_DELAY_MS)
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

        override fun onRmsChanged(rmsdB: Float) {
            // Most engines report roughly 0..10, louder to quietest. Normalise rather
            // than passing raw dB through, so the waveform's amplitude means the same
            // thing on every device.
            _rmsLevel.value = (rmsdB / 10f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Capture is over but the result is still in flight. Holding a distinct
            // processing state stops the UI offering "record" again mid-answer.
            _isListening.value = false
            _isProcessing.value = true
            _rmsLevel.value = 0f
        }

        override fun onError(error: Int) {
            // The code, never the audio or the transcript: which failure occurred is
            // diagnostic, what the user said is not.
            Log.w(TAG, "Recognition failed with error code $error")

            _isListening.value = false
            _isProcessing.value = false
            _rmsLevel.value = 0f

            when (error) {
                // The one error the app can actually do something about, rather than
                // ask the user to try again at.
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    requestLanguageDownload()
                    _errorState.value = messageFor(error)
                }

                // Recoverable by simply trying again. Surface the hint, then clear it
                // on a timer so the control is ready without the user having to
                // dismiss anything - a busy recogniser or a silent utterance is not a
                // state worth leaving a red banner up for.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    val message = messageFor(error)
                    _errorState.value = message
                    scheduleErrorReset(message)
                }

                // A client-side failure usually means the recognizer object itself is
                // in a bad state, so the next attempt rebuilds it instead of reusing a
                // poisoned instance.
                SpeechRecognizer.ERROR_CLIENT -> {
                    _errorState.value = messageFor(error)
                    teardownRecognizer()
                }

                else -> _errorState.value = messageFor(error)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()

            _isListening.value = false
            _isProcessing.value = false
            _rmsLevel.value = 0f

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
     * Asks the system to fetch the missing voice model.
     *
     * On a device whose system language is not German - an English phone in Germany,
     * say - the on-device recogniser has no German pack, and answers every attempt
     * with ERROR_LANGUAGE_UNAVAILABLE. There is nothing the user can do about that
     * from inside this app, and "try again" is advice that can never come true.
     * triggerModelDownload is the framework's own remedy for it.
     */
    private fun requestLanguageDownload() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        try {
            speechRecognizer?.triggerModelDownload(buildIntent(currentLanguage))
        } catch (e: Exception) {
            // A download that cannot be started is not worth a second error on top of
            // the one already on screen.
            Log.w(TAG, "Could not request the language download", e)
        }
    }

    /**
     * Recognition content is never logged - these describe the failure to the user
     * and say what to do about it.
     */
    private fun messageFor(error: Int): String = context.getString(
        when (error) {
            // API 33+. Both were falling through to the generic "try again", which is
            // wrong in opposite directions: one is fixable and one is permanent, and
            // neither is fixed by trying again. Android 16 answers triggerModelDownload
            // with a system consent dialog rather than a silent fetch - the pack is
            // ~118MB - so the message points at that prompt.
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.speech_error_language_unavailable
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> R.string.speech_error_language_unsupported
            SpeechRecognizer.ERROR_AUDIO -> R.string.speech_error_audio
            SpeechRecognizer.ERROR_CLIENT -> R.string.speech_error_client
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.speech_error_permission
            SpeechRecognizer.ERROR_NETWORK -> R.string.speech_error_network
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.speech_error_network_timeout
            SpeechRecognizer.ERROR_NO_MATCH -> R.string.speech_error_no_match
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.speech_error_busy
            SpeechRecognizer.ERROR_SERVER -> R.string.speech_error_server
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.speech_error_timeout
            else -> R.string.speech_error_generic
        }
    )

    companion object {
        const val DEFAULT_LANGUAGE = "de-DE"

        /** How long a recoverable error stays on screen before it clears itself. */
        private const val ERROR_RESET_DELAY_MS = 2_500L
    }
}

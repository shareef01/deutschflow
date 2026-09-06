package com.aus.deutschflow.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.aus.deutschflow.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import javax.inject.Inject

private const val TAG = "SpeechRecognizerHelper"

/**
 * Pure decision logic for evaluating on-device speech capabilities and language support.
 * Can be tested on JVM without Android runtime or static mocks.
 */
enum class SpeechCapabilityDecision {
    NO_ON_DEVICE_SERVICE,
    START_LISTENING,
    DOWNLOAD_MODEL,
    UNSUPPORTED_LANGUAGE
}

object SpeechCapabilityDecider {
    fun decide(
        isOnDeviceAvailable: Boolean,
        languageTag: String,
        installedLanguages: List<String>?,
        supportedLanguages: List<String>?
    ): SpeechCapabilityDecision {
        if (!isOnDeviceAvailable) {
            return SpeechCapabilityDecision.NO_ON_DEVICE_SERVICE
        }
        if (installedLanguages == null && supportedLanguages == null) {
            return SpeechCapabilityDecision.START_LISTENING
        }
        fun matches(list: List<String>?) = list?.any {
            it.equals(languageTag, ignoreCase = true) ||
                it.replace('_', '-').equals(languageTag.replace('_', '-'), ignoreCase = true)
        } == true

        return when {
            matches(installedLanguages) -> SpeechCapabilityDecision.START_LISTENING
            matches(supportedLanguages) -> SpeechCapabilityDecision.DOWNLOAD_MODEL
            else -> SpeechCapabilityDecision.UNSUPPORTED_LANGUAGE
        }
    }
}

/**
 * Result of querying on-device language support on Android 13+ (API 33+).
 */
sealed interface SupportResult {
    data class Supported(val isInstalled: Boolean) : SupportResult
    data object Unsupported : SupportResult
    data object Error : SupportResult
}

/**
 * Seam isolating platform SpeechRecognizer capabilities so tests can assert capability
 * decisions on JVM without requiring real device hardware or static mocking.
 */
interface SpeechRecognitionPlatformSeam {
    fun isOnDeviceRecognitionAvailable(context: Context): Boolean
    fun createOnDeviceRecognizer(context: Context): SpeechRecognizer
    fun checkRecognitionSupport(
        recognizer: SpeechRecognizer,
        intent: Intent,
        executor: Executor,
        callback: (SupportResult) -> Unit
    )
    fun triggerModelDownload(recognizer: SpeechRecognizer, intent: Intent)
}

internal object DefaultSpeechRecognitionPlatformSeam : SpeechRecognitionPlatformSeam {
    override fun isOnDeviceRecognitionAvailable(context: Context): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun createOnDeviceRecognizer(context: Context): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

    override fun checkRecognitionSupport(
        recognizer: SpeechRecognizer,
        intent: Intent,
        executor: Executor,
        callback: (SupportResult) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val language = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: "de-DE"
            recognizer.checkRecognitionSupport(
                intent,
                executor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val decision = SpeechCapabilityDecider.decide(
                            isOnDeviceAvailable = true,
                            languageTag = language,
                            installedLanguages = recognitionSupport.installedOnDeviceLanguages,
                            supportedLanguages = recognitionSupport.supportedOnDeviceLanguages
                        )
                        when (decision) {
                            SpeechCapabilityDecision.START_LISTENING ->
                                callback(SupportResult.Supported(isInstalled = true))
                            SpeechCapabilityDecision.DOWNLOAD_MODEL ->
                                callback(SupportResult.Supported(isInstalled = false))
                            SpeechCapabilityDecision.UNSUPPORTED_LANGUAGE ->
                                callback(SupportResult.Unsupported)
                            SpeechCapabilityDecision.NO_ON_DEVICE_SERVICE ->
                                callback(SupportResult.Unsupported)
                        }
                    }

                    override fun onError(error: Int) {
                        callback(SupportResult.Error)
                    }
                }
            )
        } else {
            callback(SupportResult.Supported(isInstalled = true))
        }
    }

    override fun triggerModelDownload(recognizer: SpeechRecognizer, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                recognizer.triggerModelDownload(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not request the language download", e)
            }
        }
    }
}

private class HandlerExecutor(private val handler: Handler) : Executor {
    override fun execute(command: Runnable) {
        handler.post(command)
    }
}

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

    @VisibleForTesting
    internal var platformSeam: SpeechRecognitionPlatformSeam = DefaultSpeechRecognitionPlatformSeam

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guard counter incremented on every session or cancellation to avoid async races. */
    private var sessionGeneration: Long = 0L

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
                sessionGeneration++
                val currentSession = sessionGeneration

                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null

                // Cleared before the availability check, not after it.
                _partialText.value = ""
                _finalText.value = ""
                _errorState.value = null
                _isProcessing.value = false

                // Explicit on-device check: NEVER fall back to generic/cloud recognizer.
                if (!platformSeam.isOnDeviceRecognitionAvailable(context)) {
                    _errorState.value = context.getString(R.string.speech_on_device_unavailable)
                    _isListening.value = false
                    return@post
                }

                _isListening.value = true

                val recognizer = platformSeam.createOnDeviceRecognizer(context)
                val intent = buildIntent(languageTag)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    platformSeam.checkRecognitionSupport(recognizer, intent, HandlerExecutor(mainHandler)) { supportResult ->
                        // Reject stale async response if cancelled or superseded
                        if (sessionGeneration != currentSession || !_isListening.value) {
                            recognizer.destroy()
                            return@checkRecognitionSupport
                        }

                        when (supportResult) {
                            is SupportResult.Supported -> {
                                if (supportResult.isInstalled) {
                                    recognizer.setRecognitionListener(recognitionListener)
                                    recognizer.startListening(intent)
                                    speechRecognizer = recognizer
                                } else {
                                    // Language model downloadable: trigger fetch, do not record prematurely
                                    _isListening.value = false
                                    _errorState.value = context.getString(R.string.speech_error_language_unavailable)
                                    platformSeam.triggerModelDownload(recognizer, intent)
                                    recognizer.destroy()
                                }
                            }
                            is SupportResult.Unsupported -> {
                                // Language permanently unsupported on this device
                                _isListening.value = false
                                _errorState.value = context.getString(R.string.speech_error_language_unsupported)
                                recognizer.destroy()
                            }
                            is SupportResult.Error -> {
                                // On support query error, attempt direct start with error listener
                                recognizer.setRecognitionListener(recognitionListener)
                                recognizer.startListening(intent)
                                speechRecognizer = recognizer
                            }
                        }
                    }
                } else {
                    recognizer.setRecognitionListener(recognitionListener)
                    recognizer.startListening(intent)
                    speechRecognizer = recognizer
                }
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
            sessionGeneration++
            speechRecognizer?.cancel()
            _isListening.value = false
            _isProcessing.value = false
            _partialText.value = ""
            _rmsLevel.value = 0f
        }
    }

    /**
     * Forgets the last utterance, without touching the engine.
     *
     * An attempt is described by three pieces of state, and they live in two places:
     * the scores and the verdict belong to the ViewModel, the transcript belongs here.
     * Practice moves to a new sentence without recording, so it cleared the two it
     * owned and left this one - and the words spoken for the previous sentence stayed
     * on screen underneath the new one, still labelled as what the user had just said.
     *
     * Distinct from [cancel], which also abandons a recording in progress. Nothing is
     * in flight when this is called.
     */
    fun clearTranscript() {
        _partialText.value = ""
        _finalText.value = ""
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
            sessionGeneration++
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
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
            _rmsLevel.value = (rmsdB / 10f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _isListening.value = false
            _isProcessing.value = true
            _rmsLevel.value = 0f
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Recognition failed with error code $error")

            _isListening.value = false
            _isProcessing.value = false
            _rmsLevel.value = 0f

            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    requestLanguageDownload()
                    _errorState.value = messageFor(error)
                }

                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    val message = messageFor(error)
                    _errorState.value = message
                    scheduleErrorReset(message)
                }

                SpeechRecognizer.ERROR_CLIENT -> {
                    _errorState.value = messageFor(error)
                    teardownRecognizer()
                }

                else -> _errorState.value = messageFor(error)
            }
        }

        override fun onResults(results: Bundle?) {
            deliverUtterance(
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
            )
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
     * Publishes a completed utterance and closes the session it belonged to.
     */
    @VisibleForTesting
    internal fun deliverUtterance(text: String) {
        _isListening.value = false
        _isProcessing.value = false
        _rmsLevel.value = 0f

        if (text.isNotBlank()) {
            _finalText.value = text
            _results.tryEmit(text)
        }
    }

    /**
     * Asks the system to fetch the missing voice model.
     */
    private fun requestLanguageDownload() {
        speechRecognizer?.let { recognizer ->
            platformSeam.triggerModelDownload(recognizer, buildIntent(currentLanguage))
        }
    }

    /**
     * Recognition content is never logged - these describe the failure to the user
     * and say what to do about it.
     */
    private fun messageFor(error: Int): String = context.getString(
        when (error) {
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

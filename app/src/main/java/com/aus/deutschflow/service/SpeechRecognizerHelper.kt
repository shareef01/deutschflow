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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
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
 * What an asynchronous [RecognitionSupportCallback] answer means for the live session.
 *
 * A single guard used to answer two different questions at once -
 * `sessionGeneration != currentSession || !_isListening.value` - and treated every
 * rejection as "drop and forget". That is right for the first condition and wrong for
 * the second: a session the user *stopped* while the capability check was still in
 * flight never attached a listener, so nothing will ever lower the "processing" flag
 * [SpeechRecognizerHelper.stopListening] raised, and the screen stays busy until the
 * next start. Naming the two cases is what lets [ABORT] clear that flag while [DROP]
 * leaves a superseding session's state untouched.
 *
 * Pure, so the distinction is testable on the JVM - the same seam pattern as
 * [SpeechCapabilityDecider].
 */
internal enum class SupportCallbackAction {
    /** The live session's answer: attach the listener and start recognition. */
    START,

    /** Superseded by a newer session, which now owns the shared state: drop silently. */
    DROP,

    /** Stopped while the check was in flight: no result is coming, so clear the flag. */
    ABORT
}

internal fun supportCallbackAction(
    sessionGeneration: Long,
    callbackSession: Long,
    isListening: Boolean
): SupportCallbackAction = when {
    // Superseding wins even when the newer session is itself not listening: it owns
    // the shared state, so neither destroy nor a flag reset may run on its behalf.
    sessionGeneration != callbackSession -> SupportCallbackAction.DROP
    !isListening -> SupportCallbackAction.ABORT
    else -> SupportCallbackAction.START
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
 * Delivers completed utterances to collectors with exactly-once, in-order semantics.
 *
 * [publish] is called from a [RecognitionListener] on the main thread, so it must
 * never block - and it must never silently drop the user's words, which is what
 * the old `tryEmit`-and-forget path did when the flow's single buffer slot was
 * taken by an utterance the collector was still chewing on (e.g. translating it
 * over the network).
 *
 * Every utterance therefore goes through one FIFO [Channel] drained by a single
 * supervised coroutine that feeds the flow. Ordering and exactly-once come from
 * there being exactly one consumer emitting in take order - an earlier design
 * parked each overflowed utterance in its own coroutine, and two parked
 * coroutines raced for the next buffer slot, delivering out of order. The
 * channel is unlimited, so `trySend` always succeeds and the caller returns
 * immediately; growth stays bounded in practice because the recognizer produces
 * utterances at speaking pace while a collector is stalled.
 *
 * [onOverflow] fires when the drainer has fallen behind - items are queuing up,
 * the exact window in which the old path lost words. Nothing is dropped any
 * more, but contention stays loud rather than silent.
 *
 * The scope uses a [SupervisorJob] so one failed delivery cannot take down the
 * ones behind it, and is cancelled from [close] when the owning helper is
 * destroyed, so a ViewModel being cleared never leaves a parked utterance
 * holding a reference to it.
 *
 * Pure kotlinx-coroutines with no Android types and an injectable dispatcher, so
 * the contention behaviour is testable on the JVM - same seam pattern as
 * [SpeechCapabilityDecider].
 */
@VisibleForTesting
internal class UtteranceDeliveryBus(
    private val onOverflow: (String) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val results: SharedFlow<String> = _results.asSharedFlow()

    /** Accepted but not yet taken by the drainer; the contention signal for [onOverflow]. */
    private val queued = AtomicInteger(0)
    private val queue = Channel<String>(Channel.UNLIMITED)

    /** Supervisor so one stuck or failing delivery never cancels its successors. */
    private val deliveryScope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        deliveryScope.launch {
            for (text in queue) {
                queued.decrementAndGet()
                _results.emit(text)
            }
        }
    }

    /**
     * Offers one completed utterance. Never blocks the caller; never silently
     * drops the utterance.
     */
    fun publish(text: String) {
        // Loud, not silent: a non-empty queue means the collector is behind, the
        // exact situation the old tryEmit path handled by losing the utterance.
        if (queued.get() > 0) onOverflow(text)
        queued.incrementAndGet()
        queue.trySend(text)
    }

    /**
     * Cancels every parked delivery. Called when the owning helper is destroyed;
     * an utterance that could not be delivered by then belongs to a session that
     * no longer exists, and keeping it parked would only pin the dead ViewModel.
     */
    fun close() {
        deliveryScope.cancel()
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

    /** Current generation, for tests that drive a captured listener against a superseding session. */
    @VisibleForTesting
    internal val currentGeneration: Long get() = sessionGeneration

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
     *
     * Delivery is delegated to [UtteranceDeliveryBus]: an utterance completing
     * while the collector is still busy is parked in a supervised coroutine
     * instead of being dropped on the floor, and reaches the collector once it
     * catches up - exactly once, in order.
     */
    @VisibleForTesting
    internal val deliveryBus = UtteranceDeliveryBus(
        onOverflow = { Log.w(TAG, "Utterance buffer full - parking delivery until the collector catches up") }
    )
    val results: SharedFlow<String> = deliveryBus.results

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
                        // Decide what this asynchronous answer means for the live session
                        // before it touches anything. A callback from a dead session must
                        // be turned away whatever happens next; a stopped one has state of
                        // its own to unwind.
                        when (supportCallbackAction(sessionGeneration, currentSession, _isListening.value)) {
                            SupportCallbackAction.DROP -> {
                                recognizer.destroy()
                                return@checkRecognitionSupport
                            }
                            SupportCallbackAction.ABORT -> {
                                // Stop arrived while the check was still out, so the mic
                                // never opened and no listener will ever fire. Nothing
                                // else will lower the "processing" flag stopListening
                                // raised - leaving it up strands the screen busy.
                                recognizer.destroy()
                                _isProcessing.value = false
                                return@checkRecognitionSupport
                            }
                            SupportCallbackAction.START -> Unit
                        }

                        // This callback runs on the main handler AFTER the enclosing
                        // try-catch in startListening has already exited, so an
                        // exception here is an uncaught main-thread crash. The
                        // recognizer calls below can still throw - SecurityException
                        // if RECORD_AUDIO is revoked mid-flight, IllegalStateException
                        // from a torn-down engine - so the body gets its own net.
                        try {
                            when (supportResult) {
                                is SupportResult.Supported -> {
                                    if (supportResult.isInstalled) {
                                        recognizer.setRecognitionListener(buildRecognitionListener(currentSession))
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
                                    recognizer.setRecognitionListener(buildRecognitionListener(currentSession))
                                    recognizer.startListening(intent)
                                    speechRecognizer = recognizer
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not start recognition after support check", e)
                            // This recognizer never became speechRecognizer, so
                            // nothing else will ever tear it down.
                            recognizer.destroy()
                            _errorState.value = context.getString(R.string.speech_start_failed)
                            _isListening.value = false
                            _isProcessing.value = false
                        }
                    }
                } else {
                    recognizer.setRecognitionListener(buildRecognitionListener(currentSession))
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
            // An utterance still parked behind a busy collector belongs to this
            // dead session; releasing it also releases the ViewModel it pins.
            deliveryBus.close()
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

    /**
     * Builds a listener bound to one recognition session.
     *
     * startListening() captures the session's [sessionGeneration]; the returned
     * listener rejects any callback that belongs to an earlier generation — a
     * superseded recogniser's in-flight callback — before it can flip a shared
     * StateFlow or drive a late release() against the live attempt's gate.
     */
    @VisibleForTesting
    internal fun buildRecognitionListener(sessionId: Long): RecognitionListener = object : RecognitionListener {

        private fun isStale(): Boolean = sessionGeneration != sessionId

        override fun onReadyForSpeech(params: Bundle?) {
            if (isStale()) return
            _isListening.value = true
            _errorState.value = null
        }

        override fun onBeginningOfSpeech() {
            if (isStale()) return
            _partialText.value = ""
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (isStale()) return
            _rmsLevel.value = (rmsdB / 10f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (isStale()) return
            _isListening.value = false
            _isProcessing.value = true
            _rmsLevel.value = 0f
        }

        override fun onError(error: Int) {
            if (isStale()) return
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
            if (isStale()) return
            deliverUtterance(
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
            )
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (isStale()) return
            partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.let { _partialText.value = it }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Publishes a completed utterance and closes the session it belonged to.
     *
     * The utterance itself goes through [UtteranceDeliveryBus]: called here on the
     * main thread, so publishing must not block - and must not drop the user's
     * words when the collector is a beat behind.
     */
    @VisibleForTesting
    internal fun deliverUtterance(text: String) {
        _isListening.value = false
        _isProcessing.value = false
        _rmsLevel.value = 0f

        if (text.isNotBlank()) {
            _finalText.value = text
            deliveryBus.publish(text)
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

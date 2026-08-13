package com.aus.deutschflow.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.aus.deutschflow.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TTSHelper"

@Singleton
class TTSHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * [CONNECTING] is the only state in which silence is correct - the engine is on
     * its way and the text is worth holding. The two used to be indistinguishable:
     * a single `isReady` flag meant "not ready yet" and "will never be ready" looked
     * the same, and the second one made every Speak button silently do nothing for
     * the life of the process.
     */
    private enum class State { CONNECTING, READY, UNAVAILABLE }

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var state = State.CONNECTING

    private val lock = Any()

    /** Text asked for while the engine was connecting, spoken as soon as it is. */
    @Volatile
    private var pendingText: String? = null

    /**
     * Set when the user asked to hear something and the engine could not oblige.
     *
     * Screens that speak render this. A device with no German voice data should not
     * be greeted with an error on launch, so it is only raised once somebody has
     * actually pressed a Speak button.
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        initialize()
    }

    /**
     * The engine used to be built in a field initializer with `this` as the
     * listener, and the callback then read that same field. If the engine connected
     * before the assignment landed, setLanguage() ran on null and the ready flag
     * stayed false forever.
     *
     * The callback closes over a local reference instead.
     */
    private fun initialize() {
        synchronized(lock) {
            state = State.CONNECTING
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status -> onEngineInit(engine, status) }
            tts = engine
        }
    }

    /**
     * Why the engine could not speak, when it could not.
     *
     * These are two different problems with two different remedies, and they were one
     * message. A device can have no synthesiser selected at all - which is the state a
     * Pixel was found in, with Google's engine installed but never set as the default -
     * and telling that user to install a German voice sends them to fix something that
     * is not broken. The other case is the opposite: engine fine, German absent.
     */
    private enum class Failure {
        /** No engine bound: none installed, or none chosen as the system default. */
        NO_ENGINE,

        /** An engine answered, but it has no German. */
        NO_GERMAN
    }

    private fun onEngineInit(engine: TextToSpeech?, status: Int) {
        val failure = when {
            engine == null || status != TextToSpeech.SUCCESS -> Failure.NO_ENGINE
            else -> when (engine.setLanguage(Locale.GERMAN)) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> Failure.NO_GERMAN
                else -> null
            }
        }

        val queued = pendingText
        pendingText = null

        if (failure == null) {
            state = State.READY
            _error.value = null
            queued?.let { engine?.speak(it, TextToSpeech.QUEUE_FLUSH, null, null) }
        } else {
            state = State.UNAVAILABLE
            Log.w(TAG, "Text-to-speech unavailable: $failure (init status $status)")
            if (queued != null) {
                _error.value = context.getString(
                    when (failure) {
                        Failure.NO_ENGINE -> R.string.tts_no_engine
                        Failure.NO_GERMAN -> R.string.tts_no_german
                    }
                )
            }
        }
    }

    /**
     * Clears a failure once the user has left the screen that caused it.
     *
     * The error is a @Singleton StateFlow shared by every screen that speaks, so a
     * failure raised by Study's autoplay was still on screen in the library, where
     * nothing had been asked to speak at all.
     */
    fun dismissError() {
        _error.value = null
    }

    fun speak(text: String) {
        if (text.isBlank()) return

        when (state) {
            State.READY -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)

            // Spoken by onEngineInit the moment the engine answers.
            State.CONNECTING -> pendingText = text

            // Voice data can be installed while the app is running, and the engine is
            // also torn down when the last Activity finishes while this @Singleton
            // lives on. So retry rather than staying mute - and if the retry fails
            // too, onEngineInit says so, because this text is a user asking to hear it.
            State.UNAVAILABLE -> {
                pendingText = text
                restart()
            }
        }
    }

    private fun restart() {
        synchronized(lock) {
            tts?.shutdown()
            tts = null
            initialize()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            // UNAVAILABLE rather than a state of its own: the next speak() should
            // rebuild the engine, which is exactly what UNAVAILABLE already means.
            state = State.UNAVAILABLE
            pendingText = null
            _error.value = null
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }
}

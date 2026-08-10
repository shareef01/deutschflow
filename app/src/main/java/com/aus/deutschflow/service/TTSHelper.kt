package com.aus.deutschflow.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
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

    private fun onEngineInit(engine: TextToSpeech?, status: Int) {
        val ready = when {
            engine == null -> false
            status != TextToSpeech.SUCCESS -> false
            else -> when (engine.setLanguage(Locale.GERMAN)) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> false
                else -> true
            }
        }

        val queued = pendingText
        pendingText = null

        if (ready) {
            state = State.READY
            _error.value = null
            queued?.let { engine?.speak(it, TextToSpeech.QUEUE_FLUSH, null, null) }
        } else {
            state = State.UNAVAILABLE
            Log.w(TAG, "German text-to-speech is unavailable (init status $status)")
            if (queued != null) _error.value = UNAVAILABLE
        }
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

    companion object {
        const val UNAVAILABLE =
            "German speech isn't available on this device. Install a German voice in " +
                "Android's text-to-speech settings."
    }
}

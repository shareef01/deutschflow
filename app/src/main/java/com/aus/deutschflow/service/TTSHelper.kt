package com.aus.deutschflow.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Transient focus for one spoken phrase: the app asks for the audio channel,
     * says its piece, and hands it back. GAIN_TRANSIENT_MAY_DUCK rather than a full
     * GAIN so a podcast keeps playing quietly underneath a two-word example, and so
     * the recording screens can still take the microphone when they need it.
     */
    private val audioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    @Volatile
    private var hasAudioFocus = false

    /** Focus is released the moment an utterance finishes or fails. */
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = abandonAudioFocus()

        // The single-argument onError is the only abstract hook, and the framework
        // marks it deprecated in favour of the two-argument form it does not require
        // a subclass to implement. Either way the focus must be handed back.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = abandonAudioFocus()
    }

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
        // setLanguage is a round trip to the engine, and it happens before the lock is
        // taken: holding the lock across it would block a speak() on the main thread
        // for the length of that call.
        val failure = when {
            engine == null || status != TextToSpeech.SUCCESS -> Failure.NO_ENGINE
            else -> when (engine.setLanguage(Locale.GERMAN)) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> Failure.NO_GERMAN
                else -> null
            }
        }

        // The state and the queued text move together, under the same lock speak()
        // takes. Apart, a speak() that had just read CONNECTING could store its text an
        // instant after this stopped looking for it - and the phrase was dropped with
        // nothing left to speak it, on exactly the first tap after a cold start.
        val queued = synchronized(lock) {
            state = if (failure == null) State.READY else State.UNAVAILABLE
            pendingText.also { pendingText = null }
        }

        if (failure == null) {
            _error.value = null
            engine?.setOnUtteranceProgressListener(progressListener)
            queued?.let {
                requestAudioFocus()
                engine?.speak(it, TextToSpeech.QUEUE_FLUSH, null, utteranceIdFor(it))
            }
        } else {
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

        // Reading the state and acting on it is one step, under the lock onEngineInit
        // takes: between them, the engine can finish connecting, and a text queued
        // after that happened would have had nobody left to speak it.
        val speakNow = synchronized(lock) {
            when (state) {
                State.READY -> true

                // Spoken by onEngineInit the moment the engine answers.
                State.CONNECTING -> {
                    pendingText = text
                    false
                }

                // Voice data can be installed while the app is running, and the engine
                // is also torn down when the last Activity finishes while this
                // @Singleton lives on. So retry rather than staying mute - and if the
                // retry fails too, onEngineInit says so, because this text is a user
                // asking to hear it.
                State.UNAVAILABLE -> {
                    pendingText = text
                    restart()
                    false
                }
            }
        }

        // Outside the lock: speak() is called from the main thread, and this is a call
        // into the engine.
        if (speakNow) {
            requestAudioFocus()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceIdFor(text))
        }
    }

    private fun restart() {
        synchronized(lock) {
            abandonAudioFocus()
            tts?.shutdown()
            tts = null
            initialize()
        }
    }

    /**
     * Stops the current phrase without tearing the engine down.
     *
     * The recording screens call this before they open the microphone: a German
     * phrase still playing would be picked up by the recogniser and scored against
     * the user's own voice.
     */
    fun stop() {
        synchronized(lock) {
            abandonAudioFocus()
            tts?.stop()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            // UNAVAILABLE rather than a state of its own: the next speak() should
            // rebuild the engine, which is exactly what UNAVAILABLE already means.
            state = State.UNAVAILABLE
            pendingText = null
            _error.value = null
            abandonAudioFocus()
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }

    /** Best-effort: if another app holds the channel, the phrase still plays. */
    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        hasAudioFocus = audioManager.requestAudioFocus(audioFocusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
            hasAudioFocus = false
        }
    }

    /** A fresh id per phrase so a queued utterance is never mistaken for the last. */
    private fun utteranceIdFor(text: String): String =
        "deutschflow-${text.hashCode()}-${System.nanoTime()}"
}

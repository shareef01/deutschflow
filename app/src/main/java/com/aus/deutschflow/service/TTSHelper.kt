package com.aus.deutschflow.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TTSHelper"

@Singleton
class TTSHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var tts: TextToSpeech? = null

    @Volatile
    private var isReady = false

    init {
        initialize()
    }

    /**
     * The engine used to be built in a field initializer with `this` as the
     * listener, and the callback then read that same field. If the engine connected
     * before the assignment landed, setLanguage() ran on null, the ready flag stayed
     * false forever, and every speak() call after that silently did nothing.
     *
     * The callback now closes over a local reference instead.
     */
    private fun initialize() {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            isReady = if (status == TextToSpeech.SUCCESS) {
                when (engine?.setLanguage(Locale.GERMAN)) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                        Log.w(TAG, "German voice data is unavailable on this device")
                        false
                    }
                    null -> false
                    else -> true
                }
            } else {
                Log.w(TAG, "Text-to-speech failed to initialise: status $status")
                false
            }
        }
        tts = engine
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!isReady) {
            // The engine may have been shut down when the app went to the background.
            if (tts == null) initialize()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun shutdown() {
        isReady = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

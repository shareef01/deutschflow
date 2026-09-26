package com.aus.deutschflow.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.TTSHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import java.text.Normalizer
import javax.inject.Inject

private const val TAG = "PracticeViewModel"

@Immutable
data class WordResult(val word: String, val isCorrect: Boolean)

/**
 * How well the attempt matched, as a value rather than a sentence.
 *
 * The screen used to decide which colour to use with
 * `feedback.startsWith("Excellent")` - a comparison against English prose, which
 * would have silently picked the failure colour the moment the string was
 * translated. The wording now lives in resources and only the level crosses here.
 */
@Immutable
enum class PracticeFeedback { NONE, PERFECT, GOOD, KEEP_GOING }

@HiltViewModel
class PracticeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speechRecognizerHelper: SpeechRecognizerHelper,
    private val vocabularyDao: VocabularyDao,
    private val preferenceManager: PreferenceManager,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    /**
     * Waits for the dialect, then opens the mic; single-flight across attempts.
     * The recogniser streams re-arm the gate on a terminal path (init below).
     */
    internal var startOrchestrator = StartSessionOrchestrator(preferenceManager.selectedDialect)

    /** The attempt id that owns the live recogniser session; drives ownership-tagged release(). */
    private var currentAttempt: Long = 0L

    /** Start error kept separate from the recogniser's so it clears per attempt. */
    private val _startError = MutableStateFlow<String?>(null)

    /** _startError, observable independent of the recogniser's own errors. */
    internal val startError: StateFlow<String?> = _startError

    val partialText: StateFlow<String> = speechRecognizerHelper.partialText
    val finalText: StateFlow<String> = speechRecognizerHelper.finalText
    val isListening: StateFlow<Boolean> = speechRecognizerHelper.isListening
    val isProcessing: StateFlow<Boolean> = speechRecognizerHelper.isProcessing

    /** Input level 0..1 for the live waveform; read in a draw phase, not composition. */
    val rmsLevel: StateFlow<Float> = speechRecognizerHelper.rmsLevel
    /**
     * One error surface for the screen: the start attempt, the microphone, or the
     * voice engine — whichever last had something to say. The start error is
     * per-attempt, so a dialect-read failure does not linger next to a success.
     */
    val errorState: StateFlow<String?> = combine(
        _startError,
        speechRecognizerHelper.errorState,
        ttsHelper.error
    ) { startError, recognition, speech -> startError ?: recognition ?: speech }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Whether the microphone was refused, kept apart from [errorState].
     *
     * errorState also carries the denial text, but it is shared with unrelated
     * recognition/TTS failures and is cleared by the next attempt, so it is not a
     * stable anchor for an "Open Settings" affordance. Mirrors TranscriptViewModel.
     */
    private val _permissionDenied = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = _permissionDenied

    private val _targetSentence = MutableStateFlow("Ich lerne Deutsch.")
    val targetSentence: StateFlow<String> = _targetSentence

    private val _feedback = MutableStateFlow(PracticeFeedback.NONE)
    val feedback: StateFlow<PracticeFeedback> = _feedback

    private val _wordResults = MutableStateFlow<List<WordResult>>(emptyList())
    val wordResults: StateFlow<List<WordResult>> = _wordResults

    init {
        loadRandomTarget()

        // Scoring runs when the utterance actually arrives. Reading finalText right
        // after stopPractice() scored the *previous* attempt against this sentence.
        speechRecognizerHelper.results
            .onEach { evaluatePronunciation(it) }
            .launchIn(viewModelScope)

        // Release the startPractice gate. The recogniser flips isListening/isProcessing
        // and errorState from its handler thread; collecting them here clears the gate
        // without polling. Recognition-only errorState (not the screen's combined
        // errorState) is used so a stale TTS error cannot drop the gate mid-attempt.
        speechRecognizerHelper.isListening
            .onEach { if (it) startOrchestrator.release(currentAttempt) }
            .launchIn(viewModelScope)
        speechRecognizerHelper.isProcessing
            .onEach { if (it) startOrchestrator.release(currentAttempt) }
            .launchIn(viewModelScope)
        speechRecognizerHelper.errorState
            .onEach { if (it != null) startOrchestrator.release(currentAttempt) }
            .launchIn(viewModelScope)
    }

    /**
     * Picks something real to say.
     *
     * This used to wrap the entry in a template, so the sentence to pronounce came
     * out as "Ich moechte mehr ueber '<your whole saved sentence>' lernen." - a
     * question about the material rather than the material, and unreadable once
     * entries were sentences rather than single words. The example the model wrote
     * for the entry is a real German sentence; the entry itself is one too.
     */
    private fun loadRandomTarget() {
        // Guarded: a corrupted database throws out of the DAO read, and this runs
        // from init - an uncaught failure there killed the process on the way to
        // the screen. The banner is shared from the two helpers' flows, so there
        // is nowhere here to put the news; the fallback sentence stays on screen.
        launchGuarded(TAG) {
            vocabularyDao.getAllVocabulary().firstOrNull()?.let { list ->
                if (list.isNotEmpty()) {
                    val chosenItem = selectWeightedVocabulary(list) ?: list.random()
                    _targetSentence.value =
                        chosenItem.exampleSentence.ifBlank { chosenItem.germanText }
                }
            }
            _wordResults.value = emptyList()
            _feedback.value = PracticeFeedback.NONE
            // The third piece of the last attempt, and the one that does not live here:
            // the transcript is the recogniser's. Clearing only the two above put the
            // new sentence on screen above the words spoken for the old one, which the
            // result card presents as what the user just said.
            speechRecognizerHelper.clearTranscript()
        }
    }

    fun startPractice() {
        val started = startOrchestrator.start(
            scope = viewModelScope,
            onStarted = { dialect ->
                _wordResults.value = emptyList()
                _feedback.value = PracticeFeedback.NONE
                // Stop any German playback before the microphone opens, or the
                // engine's own voice would be recognised as the user's.
                ttsHelper.stop()
                speechRecognizerHelper.startListening(dialect)
            },
            onError = { _startError.value = context.getString(R.string.speech_start_failed) },
            onAccepted = { attempt ->
                // Accepted new attempt: clear any stale start error and tag the live
                // attempt so recogniser terminal events can only re-arm this attempt's gate.
                _startError.value = null
                currentAttempt = attempt
                _permissionDenied.value = false
            },
        )
        if (started == null) {
            Log.w(TAG, "startPractice ignored: a recognition session is already starting")
            return
        }
    }

    fun stopPractice() {
        speechRecognizerHelper.stopListening()
    }

    /** Called when the screen leaves composition or the app is backgrounded. */
    fun cancelListening() {
        startOrchestrator.cancel()
        speechRecognizerHelper.cancel()
    }

    /** The user refused the microphone, so say so rather than doing nothing. */
    fun onPermissionDenied() {
        _permissionDenied.value = true
        speechRecognizerHelper.reportPermissionDenied()
    }

    private fun evaluatePronunciation(spokenText: String) {
        val (results, feedback) = evaluateMatch(_targetSentence.value, spokenText)
        _wordResults.value = results
        _feedback.value = feedback
    }

    fun nextSentence() {
        loadRandomTarget()
    }

    fun speak(text: String) {
        // The recogniser's error outlives the attempt that caused it, and this
        // screen's banner prefers it to the voice engine's, so a stale one would hide
        // whatever this request has to report. The banner should belong to the action
        // the user just took.
        speechRecognizerHelper.dismissError()
        ttsHelper.speak(text)
    }

    /** Called on entry, so a failure from another screen does not greet the user here. */
    fun dismissTtsError() {
        ttsHelper.dismissError()
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

    override fun onCleared() {
        startOrchestrator.clear()
        speechRecognizerHelper.destroy()
    }

    companion object {
        private const val TAG = "PracticeViewModel"

        val TOKEN_REGEX = Regex("[\\p{L}\\p{M}\\p{N}]+(?:[-'’][\\p{L}\\p{M}\\p{N}]+)*")

        /**
         * Folds a word to the form both spellings of it share.
         *
         * German has a standard transliteration for keyboards without umlauts - ue for
         * ü, oe for ö, ae for ä, ss for ß - and it is what anyone typing German on an
         * English keyboard produces. The recogniser, meanwhile, always returns the
         * umlaut. So a word saved by hand as "Uebung" never matched the "Übung" that
         * came back from the microphone, and Practice told the user their pronunciation
         * was wrong when it had been perfect. That is the one thing the screen exists
         * to judge, so it judged it backwards.
         *
         * lowercase() is locale-invariant in Kotlin, which matters here: under a Turkish
         * locale a default-locale lowercase would map I to a dotless ı and stop matching.
         * Apostrophes are stripped so spoken transcripts like "gehts" match "geht's".
         */
        fun foldGerman(word: String): String = Normalizer.normalize(word, Normalizer.Form.NFC)
            .lowercase()
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
            .replace(Regex("['’]"), "")

        fun tokenize(text: String): List<String> {
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
            return TOKEN_REGEX.findAll(normalized).map { it.value }.toList()
        }

        fun selectWeightedVocabulary(items: List<VocabularyEntity>): VocabularyEntity? {
            if (items.isEmpty()) return null
            if (items.size == 1) return items[0]

            val weights = items.map { item ->
                val interval = maxOf(1, item.interval)
                val ease = item.easeFactor.coerceIn(1.3f, 3.0f)
                val intervalFactor = 1.0 / Math.sqrt(interval.toDouble())
                val difficultyFactor = 3.5 - ease
                maxOf(0.1, intervalFactor * difficultyFactor)
            }

            val totalWeight = weights.sum()
            var threshold = Math.random() * totalWeight

            for (i in items.indices) {
                threshold -= weights[i]
                if (threshold <= 0.0) {
                    return items[i]
                }
            }
            return items.last()
        }

        /**
         * Scores [spokenText] against [targetSentence], in order.
         *
         * What this measures, stated plainly because the feature used to claim more:
         * how much of the target sentence the *recogniser* reported hearing. It is a
         * recall and intelligibility check, not phoneme-level pronunciation scoring -
         * neither SpeechRecognizer nor the Web Speech API exposes per-phoneme
         * confidence, so that would need a forced-alignment model on the device. A
         * speech engine's language model also resolves ambiguous audio toward
         * plausible sentences, so it will often report the word you meant even when
         * you said it poorly. Worth knowing when reading the result.
         *
         * The matching is a longest-common-subsequence alignment rather than the set
         * membership this used to do, which was wrong in two ways a learner would
         * notice: order was ignored, so saying the sentence backwards scored perfect;
         * and repetition was ignored, so a target containing "die" twice was satisfied
         * by saying it once. An LCS fixes both at once, because a subsequence is
         * ordered and consumes each match.
         *
         * Extracted from the ViewModel so it can be tested without constructing any
         * Android dependencies — same pattern as [StudyViewModel.nextStreak].
         */
        internal fun evaluateMatch(
            targetSentence: String,
            spokenText: String
        ): Pair<List<WordResult>, PracticeFeedback> {
            val targetWords = tokenize(targetSentence)
            val spokenWords = tokenize(spokenText).map { foldGerman(it) }

            val matched = alignedTargetIndices(targetWords.map { foldGerman(it) }, spokenWords)

            val results = targetWords.mapIndexed { index, targetWord ->
                WordResult(
                    // The target as it was written, not as it was folded: the user reads
                    // this back, and showing them "uebung" for a word they saved as
                    // "Übung" would be a second, more visible wrong answer.
                    word = targetWord,
                    isCorrect = index in matched
                )
            }

            val correctCount = results.count { it.isCorrect }
            val feedback = when {
                results.isEmpty() -> PracticeFeedback.NONE
                correctCount == results.size -> PracticeFeedback.PERFECT
                // Three quarters, not half. "Most words were clear" was reported for
                // getting half a sentence right, which is not most of anything.
                correctCount * 4 >= results.size * 3 -> PracticeFeedback.GOOD
                else -> PracticeFeedback.KEEP_GOING
            }

            return Pair(results, feedback)
        }

        /**
         * Which target positions appear, in order, in what was heard.
         *
         * Standard longest-common-subsequence over the two folded token lists, then a
         * walk back through the table to recover which target indices were matched.
         * O(target x spoken), which for one sentence is nothing.
         */
        private fun alignedTargetIndices(target: List<String>, spoken: List<String>): Set<Int> {
            if (target.isEmpty() || spoken.isEmpty()) return emptySet()

            // lengths[i][j] = LCS length of target[i..] and spoken[j..]
            val lengths = Array(target.size + 1) { IntArray(spoken.size + 1) }
            for (i in target.indices.reversed()) {
                for (j in spoken.indices.reversed()) {
                    lengths[i][j] = if (target[i] == spoken[j]) {
                        lengths[i + 1][j + 1] + 1
                    } else {
                        maxOf(lengths[i + 1][j], lengths[i][j + 1])
                    }
                }
            }

            val matched = mutableSetOf<Int>()
            var i = 0
            var j = 0
            while (i < target.size && j < spoken.size) {
                when {
                    target[i] == spoken[j] -> {
                        matched.add(i)
                        i++
                        j++
                    }
                    lengths[i + 1][j] >= lengths[i][j + 1] -> i++
                    else -> j++
                }
            }
            return matched
        }
    }
}

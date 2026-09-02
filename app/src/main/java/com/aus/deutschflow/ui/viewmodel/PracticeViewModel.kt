package com.aus.deutschflow.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.TTSHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val speechRecognizerHelper: SpeechRecognizerHelper,
    private val vocabularyDao: VocabularyDao,
    private val preferenceManager: PreferenceManager,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    val partialText: StateFlow<String> = speechRecognizerHelper.partialText
    val finalText: StateFlow<String> = speechRecognizerHelper.finalText
    val isListening: StateFlow<Boolean> = speechRecognizerHelper.isListening
    val isProcessing: StateFlow<Boolean> = speechRecognizerHelper.isProcessing

    /** Input level 0..1 for the live waveform; read in a draw phase, not composition. */
    val rmsLevel: StateFlow<Float> = speechRecognizerHelper.rmsLevel
    /**
     * One error surface for the screen: whichever of the microphone or the voice
     * engine last had something to say. Both are reasons the user is looking at a
     * control that did not do what they expected.
     */
    val errorState: StateFlow<String?> = combine(
        speechRecognizerHelper.errorState,
        ttsHelper.error
    ) { recognition, speech -> recognition ?: speech }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
        viewModelScope.launch {
            vocabularyDao.getAllVocabulary().firstOrNull()?.let { list ->
                if (list.isNotEmpty()) {
                    val randomItem = list.random()
                    _targetSentence.value =
                        randomItem.exampleSentence.ifBlank { randomItem.germanText }
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
        viewModelScope.launch {
            _wordResults.value = emptyList()
            _feedback.value = PracticeFeedback.NONE
            // Stop any German playback before the microphone opens, or the engine's
            // own voice would be recognised as the user's.
            ttsHelper.stop()
            speechRecognizerHelper.startListening(preferenceManager.selectedDialect.first())
        }
    }

    fun stopPractice() {
        speechRecognizerHelper.stopListening()
    }

    /** Called when the screen leaves composition or the app is backgrounded. */
    fun cancelListening() {
        speechRecognizerHelper.cancel()
    }

    /** The user refused the microphone, so say so rather than doing nothing. */
    fun onPermissionDenied() {
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

    override fun onCleared() {
        speechRecognizerHelper.destroy()
    }

    companion object {
        val WORD_SPLIT = Regex("\\s+")
        val NON_LETTERS = Regex("[^a-zA-ZäöüÄÖÜß]")

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
         */
        private fun String.foldGerman(): String = lowercase()
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")

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
            val targetWords = targetSentence.split(WORD_SPLIT)
                .map { it.replace(NON_LETTERS, "") }
                .filter { it.isNotBlank() }

            val spokenWords = spokenText.split(WORD_SPLIT)
                .map { it.replace(NON_LETTERS, "") }
                .filter { it.isNotBlank() }
                .map { it.foldGerman() }

            val matched = alignedTargetIndices(targetWords.map { it.foldGerman() }, spokenWords)

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

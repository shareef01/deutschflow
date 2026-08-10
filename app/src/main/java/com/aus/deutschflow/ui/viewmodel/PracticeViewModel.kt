package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.service.VocabularyProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WordResult(val word: String, val isCorrect: Boolean)

/**
 * How well the attempt matched, as a value rather than a sentence.
 *
 * The screen used to decide which colour to use with
 * `feedback.startsWith("Excellent")` - a comparison against English prose, which
 * would have silently picked the failure colour the moment the string was
 * translated. The wording now lives in resources and only the level crosses here.
 */
enum class PracticeFeedback { NONE, PERFECT, GOOD, KEEP_GOING }

@HiltViewModel
class PracticeViewModel @Inject constructor(
    private val speechRecognizerHelper: SpeechRecognizerHelper,
    private val vocabularyDao: VocabularyDao,
    private val vocabularyProcessor: VocabularyProcessor,
    private val preferenceManager: PreferenceManager,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    val partialText: StateFlow<String> = speechRecognizerHelper.partialText
    val finalText: StateFlow<String> = speechRecognizerHelper.finalText
    val isListening: StateFlow<Boolean> = speechRecognizerHelper.isListening
    val isProcessing: StateFlow<Boolean> = speechRecognizerHelper.isProcessing
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

    private fun loadRandomTarget() {
        viewModelScope.launch {
            vocabularyDao.getAllVocabulary().firstOrNull()?.let { list ->
                if (list.isNotEmpty()) {
                    val randomItem = list.random()
                    _targetSentence.value = vocabularyProcessor.generateExample(randomItem.germanText)
                }
            }
            _wordResults.value = emptyList()
            _feedback.value = PracticeFeedback.NONE
        }
    }

    fun startPractice() {
        viewModelScope.launch {
            _wordResults.value = emptyList()
            _feedback.value = PracticeFeedback.NONE
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

    fun setTarget(sentence: String) {
        _targetSentence.value = sentence
        _feedback.value = PracticeFeedback.NONE
    }

    fun nextSentence() {
        loadRandomTarget()
    }

    fun speak(text: String) {
        ttsHelper.speak(text)
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizerHelper.destroy()
    }

    companion object {
        val WORD_SPLIT = Regex("\\s+")
        val NON_LETTERS = Regex("[^a-zA-ZäöüÄÖÜß]")

        /**
         * Pure function: scores [spokenText] against [targetSentence] word-by-word.
         *
         * Each word in the target is checked for presence (case-insensitive) in the
         * spoken text. The feedback string follows the same progression the UI shows:
         * perfect match, mostly correct, or keep at it.
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

            val spokenWords = spokenText.lowercase().split(WORD_SPLIT)
                .map { it.replace(NON_LETTERS, "") }
                .filter { it.isNotBlank() }
                .toSet()

            val results = targetWords.map { targetWord ->
                WordResult(
                    word = targetWord,
                    isCorrect = spokenWords.contains(targetWord.lowercase())
                )
            }

            val correctCount = results.count { it.isCorrect }
            val feedback = when {
                results.isEmpty() -> PracticeFeedback.NONE
                correctCount == results.size -> PracticeFeedback.PERFECT
                correctCount * 2 > results.size -> PracticeFeedback.GOOD
                else -> PracticeFeedback.KEEP_GOING
            }

            return Pair(results, feedback)
        }
    }
}

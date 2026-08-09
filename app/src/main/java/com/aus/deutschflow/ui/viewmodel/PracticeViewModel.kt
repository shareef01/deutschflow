package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.service.VocabularyProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WordResult(val word: String, val isCorrect: Boolean)

@HiltViewModel
class PracticeViewModel @Inject constructor(
    private val speechRecognizerHelper: SpeechRecognizerHelper,
    private val vocabularyDao: VocabularyDao,
    private val vocabularyProcessor: VocabularyProcessor,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    val partialText: StateFlow<String> = speechRecognizerHelper.partialText
    val finalText: StateFlow<String> = speechRecognizerHelper.finalText
    val isListening: StateFlow<Boolean> = speechRecognizerHelper.isListening
    val errorState: StateFlow<String?> = speechRecognizerHelper.errorState

    private val _targetSentence = MutableStateFlow("Ich lerne Deutsch.")
    val targetSentence: StateFlow<String> = _targetSentence

    private val _feedback = MutableStateFlow("")
    val feedback: StateFlow<String> = _feedback

    private val _wordResults = MutableStateFlow<List<WordResult>>(emptyList())
    val wordResults: StateFlow<List<WordResult>> = _wordResults

    init {
        loadRandomTarget()
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
            _feedback.value = ""
        }
    }

    fun startPractice() {
        speechRecognizerHelper.startListening()
        _wordResults.value = emptyList()
        _feedback.value = ""
    }

    fun stopPractice() {
        speechRecognizerHelper.stopListening()
        evaluatePronunciation(finalText.value)
    }

    private fun evaluatePronunciation(spokenText: String) {
        val targetWords = _targetSentence.value.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-ZäöüÄÖÜß]"), "") }
            .filter { it.isNotBlank() }
        
        val spokenWords = spokenText.lowercase().split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-ZäöüÄÖÜß]"), "") }
            .filter { it.isNotBlank() }

        val results = targetWords.map { targetWord ->
            WordResult(
                word = targetWord,
                isCorrect = spokenWords.contains(targetWord.lowercase())
            )
        }
        
        _wordResults.value = results

        val correctCount = results.count { it.isCorrect }
        _feedback.value = when {
            correctCount == results.size && results.isNotEmpty() -> "Excellent! Perfect pronunciation."
            correctCount > results.size / 2 -> "Good! You got most of it."
            else -> "Keep practicing! Try to match the highlighted words."
        }
    }

    fun setTarget(sentence: String) {
        _targetSentence.value = sentence
        _feedback.value = ""
    }

    fun nextSentence() {
        loadRandomTarget()
        _feedback.value = ""
    }

    fun speak(text: String) {
        ttsHelper.speak(text)
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizerHelper.stopListening()
    }
}

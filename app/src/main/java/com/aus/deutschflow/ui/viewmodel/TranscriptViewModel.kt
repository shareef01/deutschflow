package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.TranslationHelper
import com.aus.deutschflow.service.VocabularyProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranscriptViewModel @Inject constructor(
    private val speechRecognizerHelper: SpeechRecognizerHelper,
    private val translationHelper: TranslationHelper,
    private val vocabularyProcessor: VocabularyProcessor,
    private val vocabularyDao: VocabularyDao,
    private val transcriptDao: TranscriptDao,
    private val preferenceManager: PreferenceManager
) : ViewModel() {

    val partialText: StateFlow<String> = speechRecognizerHelper.partialText
    val finalText: StateFlow<String> = speechRecognizerHelper.finalText
    val isListening: StateFlow<Boolean> = speechRecognizerHelper.isListening
    val errorState: StateFlow<String?> = speechRecognizerHelper.errorState

    private val _historyQuery = MutableStateFlow("")
    val historyQuery: StateFlow<String> = _historyQuery

    val transcriptHistory: StateFlow<List<TranscriptEntity>> = _historyQuery
        .combine(transcriptDao.getAllTranscripts()) { query, list ->
            if (query.isBlank()) {
                list
            } else {
                list.filter { it.fullText.contains(query, ignoreCase = true) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setHistoryQuery(query: String) {
        _historyQuery.value = query
    }

    private val _translation = MutableStateFlow("")
    val translation: StateFlow<String> = _translation

    private val _suggestedWords = MutableStateFlow<List<String>>(emptyList())
    val suggestedWords: StateFlow<List<String>> = _suggestedWords

    fun startListening() {
        speechRecognizerHelper.startListening()
        _translation.value = ""
        _suggestedWords.value = emptyList()
    }

    fun stopListening() {
        speechRecognizerHelper.stopListening()
        val text = finalText.value
        if (text.isNotBlank()) {
            processWithAI(text)
            saveTranscript(text)
        }
    }

    private fun processWithAI(text: String) {
        viewModelScope.launch {
            val apiKey = preferenceManager.geminiApiKey.first()
            val result = vocabularyProcessor.processText(text, apiKey)
            _translation.value = result.translation
            _suggestedWords.value = result.keywords
        }
    }

    private fun saveTranscript(text: String) {
        viewModelScope.launch {
            transcriptDao.insertTranscript(TranscriptEntity(fullText = text))
        }
    }

    fun deleteTranscript(transcript: TranscriptEntity) {
        viewModelScope.launch {
            transcriptDao.deleteTranscript(transcript)
        }
    }

    fun saveToVocabulary(german: String, english: String) {
        viewModelScope.launch {
            vocabularyDao.insertVocabulary(VocabularyEntity(germanText = german, englishTranslation = english))
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Helpers are singletons in this refactor, but we might want to reset their state
        // or just rely on the fact that SpeechRecognizerHelper/TTSHelper might need cleaning.
        // Actually, if they are singletons, they live as long as the App.
        // But for SpeechRecognizer, we should stop it if VM is cleared.
        speechRecognizerHelper.stopListening()
    }
}

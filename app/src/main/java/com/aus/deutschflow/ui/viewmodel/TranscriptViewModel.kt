package com.aus.deutschflow.ui.viewmodel

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.AIResult
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.VocabularyProcessor
import com.aus.deutschflow.ui.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranscriptViewModel @Inject constructor(
    private val speechRecognizerHelper: SpeechRecognizerHelper,
    private val vocabularyProcessor: VocabularyProcessor,
    private val vocabularyDao: VocabularyDao,
    private val transcriptDao: TranscriptDao,
    private val preferenceManager: PreferenceManager,
    private val widgetUpdater: WidgetUpdater
) : ViewModel() {

    val partialText: StateFlow<String> = speechRecognizerHelper.partialText
    val finalText: StateFlow<String> = speechRecognizerHelper.finalText
    val isListening: StateFlow<Boolean> = speechRecognizerHelper.isListening
    val errorState: StateFlow<String?> = speechRecognizerHelper.errorState

    private val _isTranslating = MutableStateFlow(false)

    /** True while the recognizer is finishing up or the translation is in flight. */
    val isBusy: StateFlow<Boolean> = combine(
        speechRecognizerHelper.isProcessing,
        _isTranslating
    ) { processing, translating -> processing || translating }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    private val _translation = MutableStateFlow("")
    val translation: StateFlow<String> = _translation

    private val _suggestedWords = MutableStateFlow<List<String>>(emptyList())
    val suggestedWords: StateFlow<List<String>> = _suggestedWords

    /**
     * The model's example for the current utterance, kept so that saving can store it.
     *
     * It was parsed and then dropped on the floor: the library screen showed a
     * randomly picked canned template in its place, for every word.
     */
    private val _example = MutableStateFlow("")

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError

    init {
        // Completed utterances arrive here, once each. Reading finalText straight
        // after stopListening() used to return the *previous* recording, because the
        // engine had not answered yet.
        speechRecognizerHelper.results
            .onEach { handleUtterance(it) }
            .launchIn(viewModelScope)
    }

    fun setHistoryQuery(query: String) {
        _historyQuery.value = query
    }

    fun startListening() {
        viewModelScope.launch {
            _translation.value = ""
            _suggestedWords.value = emptyList()
            _example.value = ""
            _aiError.value = null
            speechRecognizerHelper.startListening(preferenceManager.selectedDialect.first())
        }
    }

    fun stopListening() {
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

    /**
     * Internal rather than private so a test can drive a completed utterance through
     * it. The alternative is starting real speech recognition, which no test can do.
     */
    @VisibleForTesting
    internal suspend fun handleUtterance(text: String) {
        transcriptDao.insertTranscript(TranscriptEntity(fullText = text))

        _isTranslating.value = true
        try {
            when (val result = vocabularyProcessor.processText(text, preferenceManager.apiKey.first())) {
                is AIResult.Success -> {
                    _translation.value = result.translation
                    _suggestedWords.value = result.keywords
                    _example.value = result.example
                }
                is AIResult.Failure -> {
                    // Never let a failure reach the translation field: the Save button
                    // reads it, and an error string would be filed as a translation.
                    _translation.value = ""
                    _suggestedWords.value = emptyList()
                    _example.value = ""
                    _aiError.value = result.message
                }
            }
        } finally {
            _isTranslating.value = false
        }
    }

    fun deleteTranscript(transcript: TranscriptEntity) {
        viewModelScope.launch {
            transcriptDao.deleteTranscript(transcript)
        }
    }

    fun saveToVocabulary(german: String, english: String) {
        if (german.isBlank() || english.isBlank()) return
        viewModelScope.launch {
            vocabularyDao.insertVocabulary(
                VocabularyEntity(
                    germanText = german,
                    englishTranslation = english,
                    exampleSentence = _example.value
                )
            )
            widgetUpdater.refresh()
        }
    }

    override fun onCleared() {
        speechRecognizerHelper.destroy()
    }
}

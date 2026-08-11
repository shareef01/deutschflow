package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.service.VocabularyProcessor
import com.aus.deutschflow.ui.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VocabularyViewModel @Inject constructor(
    private val vocabularyDao: VocabularyDao,
    private val vocabularyProcessor: VocabularyProcessor,
    private val ttsHelper: TTSHelper,
    private val widgetUpdater: WidgetUpdater
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    /** Raised when a word could not be spoken, so the screen can say why. */
    val ttsError: StateFlow<String?> = ttsHelper.error

    val vocabularyList: StateFlow<List<VocabularyEntity>> = _searchQuery
        .combine(vocabularyDao.getAllVocabulary()) { query, list ->
            if (query.isBlank()) {
                list
            } else {
                list.filter { 
                    it.germanText.contains(query, ignoreCase = true) || 
                    it.englishTranslation.contains(query, ignoreCase = true) 
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Saves a word the user typed in by hand.
     *
     * Until this existed the library could only be filled through Transcript -> Save,
     * which needs a successful AI call - so a missing or rejected API key left no
     * way to put anything in it at all, and Study, Practice and the widget all read
     * from it. Adding a word is now the one path that never leaves the device.
     */
    fun addVocabulary(german: String, english: String) {
        val germanText = german.trim()
        val translation = english.trim()
        if (germanText.isBlank() || translation.isBlank()) return

        viewModelScope.launch {
            vocabularyDao.insertVocabulary(
                VocabularyEntity(germanText = germanText, englishTranslation = translation)
            )
            widgetUpdater.refresh()
        }
    }

    fun deleteVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            vocabularyDao.deleteVocabulary(vocabulary)
            widgetUpdater.refresh()
        }
    }

    fun updateVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            vocabularyDao.updateVocabulary(vocabulary)
            widgetUpdater.refresh()
        }
    }

    /**
     * The fallback example, for words with none of their own.
     *
     * Words saved from a transcript carry the example the model wrote for them. Words
     * typed in by hand never went near the model, so they get a generated sentence -
     * which is what the detail screen used to show for every word, including the
     * ones whose real example had been parsed and discarded.
     */
    fun exampleFor(word: String): String = vocabularyProcessor.generateExample(word)

    fun speak(text: String) {
        ttsHelper.speak(text)
    }

    /** Called on entry, so a failure from another screen does not greet the user here. */
    fun dismissTtsError() {
        ttsHelper.dismissError()
    }
}

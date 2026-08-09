package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.TTSHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VocabularyViewModel @Inject constructor(
    private val vocabularyDao: VocabularyDao,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

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

    fun deleteVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            vocabularyDao.deleteVocabulary(vocabulary)
        }
    }

    fun updateVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            vocabularyDao.updateVocabulary(vocabulary)
        }
    }

    fun speak(text: String) {
        ttsHelper.speak(text)
    }
}

package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.service.DailyWordNotification
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val vocabularyDao: VocabularyDao,
    private val transcriptDao: TranscriptDao,
    private val userStatsDao: UserStatsDao,
    private val preferenceManager: PreferenceManager,
    private val dailyWordNotification: DailyWordNotification
) : ViewModel() {

    val totalVocabulary: StateFlow<Int> = vocabularyDao.getAllVocabulary()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalTranscripts: StateFlow<Int> = transcriptDao.getAllTranscripts()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val userStats: StateFlow<UserStatsEntity?> = userStatsDao.getUserStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val geminiApiKey: StateFlow<String> = preferenceManager.geminiApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val selectedDialect: StateFlow<String> = preferenceManager.selectedDialect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "de-DE")

    val isAutoPlayEnabled: StateFlow<Boolean> = preferenceManager.isAutoPlayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun saveGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            preferenceManager.saveGeminiApiKey(apiKey)
        }
    }

    fun saveDialect(dialect: String) {
        viewModelScope.launch {
            preferenceManager.saveDialect(dialect)
        }
    }

    fun setAutoPlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferenceManager.setAutoPlayEnabled(enabled)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            transcriptDao.getAllTranscripts().firstOrNull()?.forEach {
                transcriptDao.deleteTranscript(it)
            }
        }
    }

    fun testNotification() {
        viewModelScope.launch {
            dailyWordNotification.showNotification()
        }
    }
}

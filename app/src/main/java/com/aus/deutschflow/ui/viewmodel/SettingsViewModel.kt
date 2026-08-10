package com.aus.deutschflow.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.service.DailyWordNotification
import com.aus.deutschflow.ui.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val database: AppDatabase,
    private val vocabularyDao: VocabularyDao,
    private val transcriptDao: TranscriptDao,
    private val userStatsDao: UserStatsDao,
    private val preferenceManager: PreferenceManager,
    private val dailyWordNotification: DailyWordNotification,
    private val widgetUpdater: WidgetUpdater
) : ViewModel() {

    val totalVocabulary: StateFlow<Int> = vocabularyDao.getAllVocabulary()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalTranscripts: StateFlow<Int> = transcriptDao.getAllTranscripts()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val userStats: StateFlow<UserStatsEntity?> = userStatsDao.getUserStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val apiKey: StateFlow<String> = preferenceManager.apiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val selectedDialect: StateFlow<String> = preferenceManager.selectedDialect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "de-DE")

    val isAutoPlayEnabled: StateFlow<Boolean> = preferenceManager.isAutoPlayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /**
     * A resource id, not a sentence: a ViewModel that holds prose cannot be
     * translated, and this one is asserted on by a test that would then be asserting
     * on English.
     */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message

    init {
        // Settings is the only screen that reads the key, so it is the one place
        // worth paying a Keystore round trip to re-encrypt one left in the clear by
        // an older build.
        viewModelScope.launch { preferenceManager.migrateLegacyApiKey() }
    }

    fun dismissMessage() {
        _message.value = null
    }

    @StringRes
    fun currentMessage(): Int? = _message.value

    fun saveApiKey(apiKey: String) {
        viewModelScope.launch {
            preferenceManager.saveApiKey(apiKey.trim())
            _message.value = R.string.message_api_key_saved
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

    /**
     * Wipes what the confirmation dialog promises: library, history and stats.
     *
     * This used to delete transcripts only - row by row, from a single snapshot -
     * leaving the vocabulary and the XP/streak untouched behind a dialog headed
     * "Wipe All Progress?".
     */
    fun clearAllProgress() {
        viewModelScope.launch {
            database.withTransaction {
                transcriptDao.deleteAll()
                vocabularyDao.deleteAll()
                userStatsDao.deleteAll()
            }
            widgetUpdater.refresh()
            _message.value = R.string.message_progress_cleared
        }
    }

    fun testNotification() {
        viewModelScope.launch {
            _message.value = dailyWordNotification.showNotification()
                ?: R.string.message_notification_sent
        }
    }
}

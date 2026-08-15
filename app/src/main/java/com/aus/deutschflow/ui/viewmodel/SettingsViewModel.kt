package com.aus.deutschflow.ui.viewmodel

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

    /**
     * Whether a key is stored - not the key.
     *
     * This used to expose the decrypted key itself, which the screen then put into a
     * text field on every visit. That defeated most of the point of encrypting it: the
     * plaintext was reconstructed into UI state whenever Settings was opened, sat in
     * the composition for as long as the screen lived, and was offered to whatever
     * password manager the device runs, because a filled password-typed field is
     * exactly what those look for. Nothing needs the key here; the only screen that
     * sends it reads it from PreferenceManager directly.
     */
    val hasApiKey: StateFlow<Boolean> = preferenceManager.apiKey
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    fun saveApiKey(apiKey: String) {
        viewModelScope.launch {
            // The Keystore can refuse to encrypt (e.g. the entry was dropped when the
            // lock screen was removed). Saying "saved" then is a lie that surfaces
            // later as a mysterious "no API key" translation failure.
            _message.value = if (preferenceManager.saveApiKey(apiKey.trim())) {
                R.string.message_api_key_saved
            } else {
                R.string.message_api_key_not_saved
            }
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

package com.aus.deutschflow.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.ActivityDao
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.service.CloudService
import com.aus.deutschflow.service.DailyWordNotification
import com.aus.deutschflow.service.SyncManager
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
    private val activityDao: ActivityDao,
    private val preferenceManager: PreferenceManager,
    private val dailyWordNotification: DailyWordNotification,
    private val widgetUpdater: WidgetUpdater,
    private val cloudService: CloudService,
    private val syncManager: SyncManager
) : ViewModel() {

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    /** Straight from the service. It was a two-second poll of an in-memory boolean. */
    val isCloudConnected: StateFlow<Boolean> = cloudService.isAuthenticated

    val totalVocabulary: StateFlow<Int> = vocabularyDao.countVocabulary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalTranscripts: StateFlow<Int> = transcriptDao.countTranscripts()
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
                activityDao.deleteAll()
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

    /**
     * Turns on the cloud *preview*, which is all there is to turn on.
     *
     * Took an email and a password until the audit; [CloudService] is still a stub
     * that pushes nowhere, so there was nothing to authenticate and the credentials
     * were discarded unread. Reporting a connection on the strength of that is the
     * one claim this app cannot afford to get wrong - the same reasoning
     * [performSync] already carries.
     */
    fun enableCloudPreview() {
        viewModelScope.launch {
            // Any input succeeded, so the branch that reported failure was dead.
            cloudService.signIn("", "")
            _message.value = R.string.message_cloud_connected
        }
    }

    fun signOut() {
        cloudService.signOut()
        _message.value = R.string.message_cloud_disconnected
    }

    /**
     * Runs a sync and reports what actually happened.
     *
     * [MockCloudService] is a stub - it pushes nowhere and pulls nothing - so the
     * only honest outcome today is "not available yet". Saying "your library is up
     * to date" is the one claim this app cannot afford to get wrong: the library is
     * the whole of the user's investment, and someone who believes it is backed up
     * will eventually wipe a device on the strength of it.
     */
    fun performSync() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                syncManager.performSync()
                _message.value = R.string.message_sync_unavailable
            } catch (e: Exception) {
                // The user gets the generic line; the log keeps the reason, the way
                // every other layer that swallows into a message does.
                Log.w(TAG, "Cloud sync failed", e)
                _message.value = R.string.message_cloud_failed
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private companion object {
        private const val TAG = "SettingsViewModel"
    }
}

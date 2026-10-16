package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.ActivityDao
import com.aus.deutschflow.data.local.dao.RoleplayDao
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.service.DailyWordNotification
import com.aus.deutschflow.ui.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val database: AppDatabase,
    private val vocabularyDao: VocabularyDao,
    private val transcriptDao: TranscriptDao,
    private val userStatsDao: UserStatsDao,
    private val activityDao: ActivityDao,
    private val roleplayDao: RoleplayDao,
    private val preferenceManager: PreferenceManager,
    private val dailyWordNotification: DailyWordNotification,
    private val widgetUpdater: WidgetUpdater,
    private val backupManager: com.aus.deutschflow.data.local.backup.BackupManager
) : ViewModel() {

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
    val hasApiKey: StateFlow<Boolean> = preferenceManager.apiKeyState
        .map { state ->
            when (state) {
                is PreferenceManager.ApiKeyState.Available -> state.value.isNotBlank()
                is PreferenceManager.ApiKeyState.LegacyPlaintext -> state.value.isNotBlank()
                PreferenceManager.ApiKeyState.Missing,
                PreferenceManager.ApiKeyState.Unreadable -> false
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val selectedDialect: StateFlow<String> = preferenceManager.selectedDialect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "de-DE")

    val selectedCefrLevel: StateFlow<String> = preferenceManager.selectedCefrLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

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
        launchGuarded(TAG) {
            when (preferenceManager.migrateLegacyApiKey()) {
                PreferenceManager.ApiKeyMigrationResult.FAILED ->
                    _message.value = R.string.message_api_key_migration_failed
                PreferenceManager.ApiKeyMigrationResult.NOT_NEEDED,
                PreferenceManager.ApiKeyMigrationResult.MIGRATED -> Unit
            }
        }
        // Guarded: a failing DataStore read would otherwise escape a bare launch
        // and kill the process. Log-only - the banner's wording exists for the
        // Unreadable state, not for the stream itself failing.
        launchGuarded(TAG) {
            preferenceManager.apiKeyState.collect { state ->
                if (state == PreferenceManager.ApiKeyState.Unreadable) {
                    _message.value = R.string.message_api_key_unreadable
                }
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    /**
     * One emission per key that actually reached storage, so the screen clears what the
     * user typed only once there is nothing left to lose by clearing it.
     *
     * The field used to be emptied on the tap, before the save had run. When the
     * Keystore then refused, the banner said "not saved" over an empty field and the
     * user had to find and paste the key again. An event rather than [message]: that
     * already reads "saved" after a first save, so a second identical outcome would not
     * register as a change.
     */
    private val _apiKeySaved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val apiKeySaved: SharedFlow<Unit> = _apiKeySaved.asSharedFlow()

    fun saveApiKey(apiKey: String) {
        // Guarded, with the same message a false return gives: a Keystore that
        // throws instead of returning false is still "the key couldn't be stored".
        launchGuarded(TAG, onError = { _message.value = R.string.message_api_key_not_saved }) {
            // The Keystore can refuse to encrypt (e.g. the entry was dropped when the
            // lock screen was removed). Saying "saved" then is a lie that surfaces
            // later as a mysterious "no API key" translation failure.
            if (preferenceManager.saveApiKey(apiKey.trim())) {
                _message.value = R.string.message_api_key_saved
                _apiKeySaved.emit(Unit)
            } else {
                _message.value = R.string.message_api_key_not_saved
            }
        }
    }

    /**
     * Withdraws the stored key. Every AI feature then reports "no key" until one is
     * saved again; the library and progress are untouched. See
     * [PreferenceManager.clearApiKey].
     */
    fun removeApiKey() {
        launchGuarded(TAG, onError = { _message.value = R.string.message_api_key_not_removed }) {
            _message.value = if (preferenceManager.clearApiKey()) {
                R.string.message_api_key_removed
            } else {
                R.string.message_api_key_not_removed
            }
        }
    }

    fun saveDialect(dialect: String) {
        // Guarded, log-only: a preference write is not worth a crash, and the
        // radio group still shows what is actually stored.
        launchGuarded(TAG) {
            preferenceManager.saveDialect(dialect)
        }
    }

    fun saveCefrLevel(cefrLevel: String) {
        launchGuarded(TAG) {
            preferenceManager.saveCefrLevel(cefrLevel)
        }
    }

    fun setAutoPlayEnabled(enabled: Boolean) {
        launchGuarded(TAG) {
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
        launchGuarded(TAG, onError = { _message.value = R.string.message_clear_failed }) {
            database.withTransaction {
                transcriptDao.deleteAll()
                vocabularyDao.deleteAll()
                userStatsDao.deleteAll()
                activityDao.deleteAll()
                // The saved roleplay is the user's speech too. A wipe that left a
                // conversation behind would be the one thing the dialog promises not
                // to do.
                roleplayDao.deleteAll()
                database.reviewEventDao().deleteAll()
            }
            widgetUpdater.refresh()
            _message.value = R.string.message_progress_cleared
        }
    }

    /**
     * The user refused notifications, or had refused them for good and Android no
     * longer asks. Same message showNotification gives, since the remedy is the same:
     * the system settings page is the only place left to turn them on.
     */
    fun onNotificationPermissionDenied() {
        _message.value = R.string.message_notifications_off
    }

    fun testNotification() {
        // Guarded, log-only: the outcomes the user can act on already carry their
        // own messages; a throw from the notification manager has no existing
        // string and is not worth a crash.
        launchGuarded(TAG) {
            _message.value = dailyWordNotification.showNotification()
                ?: R.string.message_notification_sent
        }
    }

    fun exportBackup(outputStream: java.io.OutputStream, passphrase: String? = null) {
        launchGuarded(TAG, onError = { _message.value = R.string.message_backup_failed }) {
            val passChars = passphrase?.takeIf { it.isNotBlank() }?.toCharArray()
            backupManager.exportToStream(outputStream, passChars)
            _message.value = R.string.message_backup_exported
        }
    }

    fun importBackup(
        inputStream: java.io.InputStream,
        passphrase: String? = null,
        onSuccess: (() -> Unit)? = null,
        onPassphraseNeeded: (() -> Unit)? = null
    ) {
        launchGuarded(TAG, onError = { _message.value = R.string.message_backup_import_failed }) {
            try {
                val passChars = passphrase?.takeIf { it.isNotBlank() }?.toCharArray()
                backupManager.importFromStream(inputStream, passChars)
                widgetUpdater.refresh()
                _message.value = R.string.message_backup_imported
                onSuccess?.invoke()
            } catch (e: com.aus.deutschflow.data.local.backup.BackupException.WrongPassphrase) {
                _message.value = R.string.message_backup_wrong_passphrase
                onPassphraseNeeded?.invoke()
            } catch (e: com.aus.deutschflow.data.local.backup.BackupException.PassphraseRequired) {
                _message.value = R.string.message_backup_passphrase_required
                onPassphraseNeeded?.invoke()
            } catch (e: com.aus.deutschflow.data.local.backup.BackupException.UnsupportedVersion) {
                _message.value = R.string.message_backup_unsupported_version
            } catch (e: Exception) {
                _message.value = R.string.message_backup_import_failed
            }
        }
    }

    private companion object {
        private const val TAG = "SettingsViewModel"
    }
}

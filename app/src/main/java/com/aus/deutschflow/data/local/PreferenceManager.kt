package com.aus.deutschflow.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

private const val TAG = "PreferenceManager"

/**
 * The store is injected rather than reached for through a Context extension.
 *
 * The extension is a process-wide singleton over one file, so every test that built
 * a PreferenceManager was reading and writing the user's own settings - and two of
 * them cleared the API key as setup or teardown. That wiped a real key off a
 * developer's device, and left GroqLiveTest with nothing to authenticate with, so the
 * one test that proves the AI path against the real service could only ever run once.
 * Tests now pass a store of their own; only [appDataStore] touches the real file.
 */
@Singleton
class PreferenceManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: KeystoreCipher
) {

    sealed interface ApiKeyState {
        data object Missing : ApiKeyState
        data class Available(val value: String) : ApiKeyState
        data class LegacyPlaintext(val value: String) : ApiKeyState
        data object Unreadable : ApiKeyState
    }

    enum class ApiKeyMigrationResult { NOT_NEEDED, MIGRATED, FAILED }

    // Encrypted, under a name of its own. A Gemini key is no use to Groq, and a
    // plaintext key is no use to the decrypting reader, so each change of meaning
    // gets a new name rather than a silent reinterpretation of the old bytes.
    private val KEY_API_KEY_ENCRYPTED = stringPreferencesKey("groq_api_key_encrypted")

    /** The plaintext entry this replaces. Read once, to migrate it, then removed. */
    private val KEY_API_KEY_LEGACY = stringPreferencesKey("groq_api_key")

    /**
     * The UTC offset, in seconds, the daily-word notification was last scheduled
     * against. Absent until the first launch that records one.
     */
    private val KEY_DAILY_WORD_ZONE_OFFSET = intPreferencesKey("daily_word_zone_offset")

    private val KEY_DIALECT = stringPreferencesKey("dialect")
    private val KEY_AUTO_PLAY = booleanPreferencesKey("auto_play")
    private val KEY_CEFR_LEVEL = stringPreferencesKey("cefr_level")

    /**
     * Decryption is a Keystore round trip, so it happens off whichever thread is
     * collecting - which is the main one, since the ViewModels collect there.
     *
     * The stored bytes are compared before that round trip is paid. DataStore
     * re-emits the whole preference set on every write, so without this the key was
     * decrypted again each time an unrelated setting changed - and Settings, which
     * holds a subscription for as long as it is on screen, is also the screen where
     * the dialect and the auto-play toggle are written.
     */
    val apiKeyState: Flow<ApiKeyState> = dataStore.data
        .map { preferences ->
            preferences[KEY_API_KEY_ENCRYPTED] to preferences[KEY_API_KEY_LEGACY]
        }
        .distinctUntilChanged()
        .map { (encrypted, legacy) ->
            when {
                encrypted != null -> cipher.decrypt(encrypted)
                    ?.let { ApiKeyState.Available(it) }
                    ?: ApiKeyState.Unreadable
                legacy != null -> ApiKeyState.LegacyPlaintext(legacy)
                else -> ApiKeyState.Missing
            }
        }
        .flowOn(Dispatchers.IO)

    /** Value-only compatibility view for the AI call sites. */
    val apiKey: Flow<String> = apiKeyState.map { state ->
        when (state) {
            is ApiKeyState.Available -> state.value
            is ApiKeyState.LegacyPlaintext -> state.value
            ApiKeyState.Missing, ApiKeyState.Unreadable -> ""
        }
    }

    /** Int.MIN_VALUE means "never recorded", which no real offset can be. */
    val dailyWordZoneOffset: Flow<Int> = dataStore.data.map { preferences ->
        preferences[KEY_DAILY_WORD_ZONE_OFFSET] ?: Int.MIN_VALUE
    }

    suspend fun setDailyWordZoneOffset(offsetSeconds: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_DAILY_WORD_ZONE_OFFSET] = offsetSeconds
        }
    }

    val selectedDialect: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_DIALECT] ?: "de-DE"
    }

    val selectedCefrLevel: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_CEFR_LEVEL] ?: ""
    }

    val isAutoPlayEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_AUTO_PLAY] ?: true
    }

    /**
     * Stores the key encrypted, and drops any plaintext copy while it is here.
     *
     * If encryption fails the key is not written at all. Falling back to plaintext
     * would defeat the point of the change, and silently: the app would keep
     * working, so nobody would ever find out.
     *
     * @return false when the key could not be stored, so the caller can say so
     * instead of claiming a save that did not happen.
     */
    suspend fun saveApiKey(apiKey: String): Boolean {
        // Guarded end to end. KeystoreCipher.encrypt swallows its own failures and
        // returns null, which the `?: return false` below handles - but the DataStore
        // write was unguarded, so an unexpected IO or corruption error there would
        // propagate out of SettingsViewModel's launch and crash the app. Catching it
        // turns any such failure into "not saved", and because the edit is atomic the
        // key already stored is left intact for the user to keep using.
        //
        // CancellationException is rethrown so this still composes with structured
        // concurrency: a scope cancellation propagates instead of being swallowed.
        val trimmed = apiKey.trim()
        val encrypted = try {
            withContext(Dispatchers.IO) { cipher.encrypt(trimmed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not encrypt the API key", e)
            null
        } ?: return false

        try {
            dataStore.edit { preferences ->
                preferences[KEY_API_KEY_ENCRYPTED] = encrypted
                preferences.remove(KEY_API_KEY_LEGACY)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // dataStore.edit is atomic per preferences file: if it threw, neither the
            // encrypted write nor the legacy removal stuck, so the prior credential
            // survives untouched.
            Log.w(TAG, "Could not store the API key", e)
            return false
        }
        return true
    }

    /**
     * Re-writes a key left in the clear by an older build, encrypted.
     *
     * Runs on the startup path (see MainApp.onCreate), not only when Settings opens.
     * It used to run only there, which meant a user who set a key and never visited
     * Settings again kept it in plaintext indefinitely - and since [apiKeyState]
     * deliberately keeps reporting such a key as usable ([ApiKeyState.LegacyPlaintext]),
     * nothing anywhere ever complained.
     *
     * Idempotent, which is what lets it run unattended at launch: a key that is
     * already encrypted yields NOT_NEEDED, so the Keystore round trip happens at most
     * once per install. Deliberately never falls back to plaintext, and writes nothing
     * at all on failure, so the legacy value is always available to retry.
     *
     * Concurrent with [saveApiKey]: the value is read before the Keystore call and
     * re-checked inside the write, so a key the user saved in between wins and this
     * becomes a no-op rather than overwriting the newer key with the stale one.
     */
    suspend fun migrateLegacyApiKey(): ApiKeyMigrationResult {
        // The legacy value has to be read outside the edit: encrypting it is a Keystore
        // round trip, and DataStore forbids suspending inside an edit block. That splits
        // what used to be one atomic step into a read, then a write - so a save that
        // lands in between must not be overwritten by the stale value. Hence the
        // re-check inside the edit below, which is what actually makes this safe to run
        // at launch alongside a save from Settings.
        val legacy = dataStore.data.first()[KEY_API_KEY_LEGACY]
            ?: return ApiKeyMigrationResult.NOT_NEEDED
        val encrypted = withContext(Dispatchers.IO) { cipher.encrypt(legacy) }
            ?: return ApiKeyMigrationResult.FAILED

        var migrated = false
        dataStore.edit { preferences ->
            // Either the user saved a new key while the Keystore was busy - in which
            // case that key is the current one and the legacy copy is already gone, so
            // there is nothing to migrate - or the legacy value is still exactly what we
            // encrypted. Anything else means it changed under us and we leave it alone.
            val current = preferences[KEY_API_KEY_LEGACY]
            if (current == null || preferences[KEY_API_KEY_ENCRYPTED] != null) return@edit
            preferences[KEY_API_KEY_ENCRYPTED] = encrypted
            preferences.remove(KEY_API_KEY_LEGACY)
            migrated = true
        }
        // A concurrent save already superseded the migration, which is a success from
        // here: there is no plaintext left behind and the newer key is intact.
        return if (migrated || dataStore.data.first()[KEY_API_KEY_LEGACY] == null) {
            ApiKeyMigrationResult.MIGRATED
        } else {
            // Nothing was written, so this recoverable legacy value remains for a later
            // retry.
            ApiKeyMigrationResult.FAILED
        }
    }

    suspend fun saveDialect(dialect: String) {
        dataStore.edit { preferences ->
            preferences[KEY_DIALECT] = dialect
        }
    }

    suspend fun saveCefrLevel(cefrLevel: String) {
        dataStore.edit { preferences ->
            preferences[KEY_CEFR_LEVEL] = cefrLevel
        }
    }

    suspend fun setAutoPlayEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_AUTO_PLAY] = enabled
        }
    }

    companion object {

        /**
         * The app's own settings file.
         *
         * The delegate behind it allows exactly one instance per file per process, so
         * this is the only way to reach the real store: a second one built over the
         * same path throws. Production gets it through Hilt; the one test that needs
         * the user's real key - GroqLiveTest - calls this directly.
         */
        fun appDataStore(context: Context): DataStore<Preferences> = context.settingsDataStore
    }
}

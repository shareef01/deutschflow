package com.aus.deutschflow.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

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

    // Encrypted, under a name of its own. A Gemini key is no use to Groq, and a
    // plaintext key is no use to the decrypting reader, so each change of meaning
    // gets a new name rather than a silent reinterpretation of the old bytes.
    private val KEY_API_KEY_ENCRYPTED = stringPreferencesKey("groq_api_key_encrypted")

    /** The plaintext entry this replaces. Read once, to migrate it, then removed. */
    private val KEY_API_KEY_LEGACY = stringPreferencesKey("groq_api_key")

    private val KEY_DIALECT = stringPreferencesKey("dialect")
    private val KEY_AUTO_PLAY = booleanPreferencesKey("auto_play")

    /**
     * Decryption is a Keystore round trip, so it happens off whichever thread is
     * collecting - which is the main one, since the ViewModels collect there.
     */
    val apiKey: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[KEY_API_KEY_ENCRYPTED]
                ?.let { cipher.decrypt(it) }
                ?: preferences[KEY_API_KEY_LEGACY]
                ?: ""
        }
        .flowOn(Dispatchers.IO)

    val selectedDialect: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_DIALECT] ?: "de-DE"
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
     */
    suspend fun saveApiKey(apiKey: String) {
        val encrypted = withContext(Dispatchers.IO) { cipher.encrypt(apiKey) } ?: return

        dataStore.edit { preferences ->
            preferences[KEY_API_KEY_ENCRYPTED] = encrypted
            preferences.remove(KEY_API_KEY_LEGACY)
        }
    }

    /**
     * Re-writes a key left in the clear by an older build, encrypted.
     *
     * Called when Settings opens, which is the only screen that cares about the key
     * and so the one place where paying for a Keystore round trip is warranted.
     */
    suspend fun migrateLegacyApiKey() {
        val legacy = dataStore.data.first()[KEY_API_KEY_LEGACY] ?: return
        saveApiKey(legacy)
    }

    suspend fun saveDialect(dialect: String) {
        dataStore.edit { preferences ->
            preferences[KEY_DIALECT] = dialect
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

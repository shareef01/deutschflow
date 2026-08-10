package com.aus.deutschflow.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class PreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
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
    val apiKey: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_API_KEY_ENCRYPTED]
                ?.let { cipher.decrypt(it) }
                ?: preferences[KEY_API_KEY_LEGACY]
                ?: ""
        }
        .flowOn(Dispatchers.IO)

    val selectedDialect: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_DIALECT] ?: "de-DE"
    }

    val isAutoPlayEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
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

        context.dataStore.edit { preferences ->
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
        val legacy = context.dataStore.data.first()[KEY_API_KEY_LEGACY] ?: return
        saveApiKey(legacy)
    }

    suspend fun saveDialect(dialect: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DIALECT] = dialect
        }
    }

    suspend fun setAutoPlayEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_PLAY] = enabled
        }
    }
}

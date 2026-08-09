package com.aus.deutschflow.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class PreferenceManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    private val KEY_DIALECT = stringPreferencesKey("dialect")
    private val KEY_AUTO_PLAY = booleanPreferencesKey("auto_play")

    val geminiApiKey: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_GEMINI_API_KEY] ?: ""
    }

    val selectedDialect: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_DIALECT] ?: "de-DE"
    }

    val isAutoPlayEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_PLAY] ?: true
    }

    suspend fun saveGeminiApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_GEMINI_API_KEY] = apiKey
        }
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

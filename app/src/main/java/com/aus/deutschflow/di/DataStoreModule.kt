package com.aus.deutschflow.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.aus.deutschflow.data.local.PreferenceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    /**
     * The settings store PreferenceManager reads and writes.
     *
     * Bound here rather than reached for inside PreferenceManager so that a test can
     * substitute a store of its own instead of operating on the user's real settings.
     */
    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = PreferenceManager.appDataStore(context)
}

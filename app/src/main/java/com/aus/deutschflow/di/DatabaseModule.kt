package com.aus.deutschflow.di

import android.content.Context
import androidx.room.Room
import com.aus.deutschflow.BuildConfig
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .apply {
                // A user's saved vocabulary is the whole point of the app, so a
                // release build must never drop it to satisfy a schema change.
                // Debug builds keep the shortcut, where churn is expected.
                @Suppress("DEPRECATION")
                if (BuildConfig.DEBUG) {
                    fallbackToDestructiveMigration()
                }
            }
            .build()

    @Provides
    fun provideVocabularyDao(database: AppDatabase): VocabularyDao = database.vocabularyDao()

    @Provides
    fun provideTranscriptDao(database: AppDatabase): TranscriptDao = database.transcriptDao()

    @Provides
    fun provideUserStatsDao(database: AppDatabase): UserStatsDao = database.userStatsDao()
}

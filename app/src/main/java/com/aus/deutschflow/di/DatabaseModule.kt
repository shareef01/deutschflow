package com.aus.deutschflow.di

import android.content.Context
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
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    fun provideVocabularyDao(database: AppDatabase): VocabularyDao {
        return database.vocabularyDao()
    }

    @Provides
    fun provideTranscriptDao(database: AppDatabase): TranscriptDao {
        return database.transcriptDao()
    }

    @Provides
    fun provideUserStatsDao(database: AppDatabase): UserStatsDao {
        return database.userStatsDao()
    }
}

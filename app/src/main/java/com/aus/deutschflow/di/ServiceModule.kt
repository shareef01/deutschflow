package com.aus.deutschflow.di

import android.content.Context
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.service.VocabularyProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideVocabularyProcessor(): VocabularyProcessor {
        return VocabularyProcessor()
    }

    @Provides
    @Singleton
    fun provideTTSHelper(@ApplicationContext context: Context): TTSHelper {
        return TTSHelper(context)
    }
}

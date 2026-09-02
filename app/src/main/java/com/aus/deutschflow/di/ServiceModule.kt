package com.aus.deutschflow.di

import com.aus.deutschflow.service.GroqHelper
import com.aus.deutschflow.service.VocabularyProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideVocabularyProcessor(languageModel: GroqHelper): VocabularyProcessor {
        return VocabularyProcessor(languageModel)
    }
}

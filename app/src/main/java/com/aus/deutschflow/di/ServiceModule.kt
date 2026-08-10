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

    /**
     * VocabularyProcessor has no @Inject constructor - it takes a default-valued
     * GroqHelper so tests can substitute one - so it needs a binding here.
     *
     * TTSHelper does not: it is already an @Singleton @Inject constructor, and the
     * @Provides that used to sit here was a second, redundant way to say so.
     */
    @Provides
    @Singleton
    fun provideVocabularyProcessor(languageModel: GroqHelper): VocabularyProcessor {
        return VocabularyProcessor(languageModel)
    }
}

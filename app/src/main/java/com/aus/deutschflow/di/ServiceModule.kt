package com.aus.deutschflow.di

import com.aus.deutschflow.service.CloudService
import com.aus.deutschflow.service.GroqHelper
import com.aus.deutschflow.service.MockCloudService
import com.aus.deutschflow.service.VocabularyProcessor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {

    @Binds
    @Singleton
    abstract fun bindCloudService(impl: MockCloudService): CloudService

    companion object {
        @Provides
        @Singleton
        fun provideVocabularyProcessor(languageModel: GroqHelper): VocabularyProcessor {
            return VocabularyProcessor(languageModel)
        }
    }
}

package com.aus.deutschflow.di

import com.aus.deutschflow.data.local.dao.VocabularyDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The system constructs app widgets, so they cannot be injected. This gives the
 * widget the same DAO Hilt hands everything else, rather than having it build a
 * second database of its own.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun vocabularyDao(): VocabularyDao
}

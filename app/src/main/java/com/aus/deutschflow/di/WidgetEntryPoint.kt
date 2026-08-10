package com.aus.deutschflow.di

import com.aus.deutschflow.service.DailyWord
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The system constructs app widgets, so they cannot be injected. This gives the
 * widget the same [DailyWord] the notification uses, rather than having it build a
 * second database of its own and pick a second word.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun dailyWord(): DailyWord
}

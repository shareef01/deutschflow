package com.aus.deutschflow.di

import com.aus.deutschflow.service.DailyWordNotification
import com.aus.deutschflow.ui.widget.WidgetUpdater
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * WorkManager constructs workers itself, so they cannot be injected.
 *
 * Same reasoning as [WidgetEntryPoint]: reach into the singleton graph rather than
 * adding hilt-work and a custom WorkerFactory for one worker.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerEntryPoint {
    fun dailyWordNotification(): DailyWordNotification
    fun widgetUpdater(): WidgetUpdater
}

package com.aus.deutschflow.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aus.deutschflow.di.WorkerEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Posts the word of the day, once a day.
 *
 * [DailyWordNotification] existed and worked, but its only caller was the "Test Daily
 * Notification" button in Settings - so the channel called "Daily German Word" and the
 * widget headed "WORD OF THE DAY" were promising a habit loop the app did not have.
 */
class DailyWordWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors
            .fromApplication(applicationContext, WorkerEntryPoint::class.java)

        // showNotification returns a message when it could not post: notifications are
        // off, or the library is empty. Neither is fixed by retrying, and neither is a
        // failure of this run - tomorrow's will try again.
        entryPoint.dailyWordNotification().showNotification()

        // The widget's own updatePeriodMillis is a day long but is not aligned to
        // anything, so without this the home screen could go on showing yesterday's
        // word for hours after the notification announced today's.
        entryPoint.widgetUpdater().refresh()
        return Result.success()
    }

    companion object {

        /**
         * Enqueued on every launch under [ExistingPeriodicWorkPolicy.KEEP], so an
         * already-scheduled run keeps its place rather than being pushed back a day
         * each time the user opens the app.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyWordWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayUntilNext(HOUR_OF_DAY), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Milliseconds from [now] until the next [hourOfDay] in the device's zone.
         *
         * Returns tomorrow's slot when today's has already passed, so installing the
         * app at 9:30am does not fire a notification thirty seconds later.
         */
        internal fun delayUntilNext(
            hourOfDay: Int,
            now: ZonedDateTime = ZonedDateTime.now()
        ): Long {
            val todaysSlot = now.withHour(hourOfDay)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)

            val target = if (todaysSlot.isAfter(now)) todaysSlot else todaysSlot.plusDays(1)
            return Duration.between(now, target).toMillis()
        }

        private const val UNIQUE_NAME = "daily-word"

        /** Late enough to be awake, early enough to still be part of the day. */
        private const val HOUR_OF_DAY = 9
    }
}

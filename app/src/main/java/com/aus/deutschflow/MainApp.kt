package com.aus.deutschflow

import android.app.Application
import com.aus.deutschflow.service.DailyWordWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MainApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Idempotent: an existing schedule is kept rather than restarted.
        DailyWordWorker.schedule(this)
    }
}

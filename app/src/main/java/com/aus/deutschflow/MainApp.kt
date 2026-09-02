package com.aus.deutschflow

import android.app.Application
import android.util.Log
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.service.DailyWordWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MainApp"

@HiltAndroidApp
class MainApp : Application() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    /**
     * For work that belongs to the process rather than to any screen.
     *
     * The one job here outlives every ViewModel by definition - it is deciding when
     * tomorrow's notification fires - so it cannot borrow a viewModelScope. A
     * handler rather than a bare launch: an uncaught throwable in a scope with no
     * parent reaches the thread's default handler, and a rescheduling failure must
     * not be a crash on launch.
     */
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.w(TAG, "Startup work failed", error)
        }
    )

    override fun onCreate() {
        super.onCreate()
        // Idempotent: an existing schedule is kept rather than restarted.
        DailyWordWorker.schedule(this)

        // KEEP means the initial delay is honoured once and the work then repeats on
        // elapsed time, so the 9am slot drifts by the offset after the user changes
        // timezone - permanently, because nothing re-enqueues it. This notices and
        // re-anchors it. No-op on every launch but the first after a move.
        appScope.launch {
            DailyWordWorker.rescheduleIfZoneChanged(this@MainApp, preferenceManager)
        }
    }
}

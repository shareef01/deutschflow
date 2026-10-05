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

        // A key left in the clear by a pre-encryption build used to be re-encrypted
        // only when the user happened to open Settings - so a user who set their key
        // and never visited that screen again kept a plaintext copy in their data
        // directory indefinitely, and apiKeyState deliberately still reports it as
        // usable (ApiKeyState.LegacyPlaintext), which is exactly why nothing surfaced.
        //
        // On the startup path instead: the app already runs startup work in this
        // scope, and this removes the dependency on the user visiting one particular
        // screen for their key to stop being plaintext.
        //
        // Safe to run concurrently with a save, and safe to run twice.
        // migrateLegacyApiKey writes the encrypted value and removes the plaintext one
        // in a single DataStore edit, so the credential is never absent and never
        // written back in the clear; if encryption fails it writes nothing and the
        // legacy value stays available to retry on the next launch. Nothing here can
        // throw past appScope's handler, which only logs.
        appScope.launch {
            preferenceManager.migrateLegacyApiKey()
        }
    }
}

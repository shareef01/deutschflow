package com.aus.deutschflow

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.service.TTSHelper
import kotlinx.coroutines.launch
import com.aus.deutschflow.ui.navigation.MainNavigation
import com.aus.deutschflow.ui.theme.DeutschflowTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var ttsHelper: TTSHelper

    @Inject
    lateinit var preferenceManager: PreferenceManager

    /**
     * No permissions are requested here.
     *
     * Every runtime permission is requested at the moment the user does the thing that
     * needs it: RECORD_AUDIO on the tap that starts recording (Practice, Transcript and
     * Roleplay all check first), POST_NOTIFICATIONS on the tap that sends a test
     * notification. Demanding them on the very first launch, before the user had seen a
     * screen, only produced the version of the prompt most likely to be denied.
     */
    /**
     * A word the daily notification or the widget asked to open, until the library
     * has opened it.
     *
     * Both used to open the app and stop: the notification announced a word and the
     * tap landed on the Transcript screen, nowhere near it.
     */
    private var pendingWordId by mutableStateOf<Int?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only on a fresh start. A recreated Activity (rotation, process death) still
        // carries the launching intent, and reopening its word would yank the user
        // back to it from wherever they had moved on to.
        if (savedInstanceState == null) pendingWordId = wordIdFrom(intent)

        // The read-aloud voice follows the dialect chosen in Settings, including a
        // change made while the app is open. Cheap: setDialect ignores repeats and
        // never starts the engine itself.
        lifecycleScope.launch {
            preferenceManager.selectedDialect.collect { ttsHelper.setDialect(it) }
        }

        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            DeutschflowTheme {
                val navController = rememberNavController()
                MainNavigation(
                    navController = navController,
                    windowSizeClass = windowSizeClass,
                    openWordId = pendingWordId,
                    onWordOpened = { pendingWordId = null }
                )
            }
        }
    }

    /** A tap while the app is already open arrives here (FLAG_ACTIVITY_SINGLE_TOP). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        wordIdFrom(intent)?.let { pendingWordId = it }
    }

    override fun onDestroy() {
        // Single-activity app: when this finishes, nothing is left to speak.
        if (isFinishing) ttsHelper.shutdown()
        super.onDestroy()
    }

    companion object {
        /** The vocabulary id to open, carried by the notification's and widget's intents. */
        const val EXTRA_WORD_ID = "com.aus.deutschflow.extra.WORD_ID"

        /**
         * The word id an intent asks for, or null. Room ids start at 1, so anything
         * else is treated as absent. This Activity is exported (it is the launcher),
         * so any app can send this extra - which is harmless: at worst it opens a word
         * that does not exist, and the library simply shows its list.
         */
        internal fun wordIdFrom(intent: Intent?): Int? =
            intent?.getIntExtra(EXTRA_WORD_ID, 0)?.takeIf { it > 0 }
    }
}

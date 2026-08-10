package com.aus.deutschflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.navigation.compose.rememberNavController
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.ui.navigation.MainNavigation
import com.aus.deutschflow.ui.theme.DeutschflowTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var ttsHelper: TTSHelper

    /**
     * No permissions are requested here.
     *
     * Both recording screens already check and request RECORD_AUDIO on the tap that
     * needs it, and Settings requests POST_NOTIFICATIONS when the user asks for a
     * notification. Demanding both on the very first launch, before the user had seen
     * a screen, only produced the version of the prompt most likely to be denied.
     */
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            DeutschflowTheme {
                val navController = rememberNavController()
                MainNavigation(
                    navController = navController,
                    windowSizeClass = windowSizeClass
                )
            }
        }
    }

    override fun onDestroy() {
        // Single-activity app: when this finishes, nothing is left to speak.
        if (isFinishing) ttsHelper.shutdown()
        super.onDestroy()
    }
}

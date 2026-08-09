package com.aus.deutschflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.content.ContextCompat
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
     * Requests every missing permission at once. Launching only the first of them
     * meant that on a fresh install, where both are missing, notifications were
     * never requested at all.
     */
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestMissingPermissions()

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

    private fun requestMissingPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }
}

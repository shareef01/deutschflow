package com.aus.deutschflow.ui.navigation

import com.aus.deutschflow.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.School
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, @StringRes val title: Int, val icon: ImageVector) {
    object Transcript : Screen("transcript", R.string.nav_transcript, Icons.Default.Mic)
    object History : Screen("history", R.string.nav_history, Icons.Default.History)
    object Vocabulary : Screen("vocabulary", R.string.nav_library, Icons.Default.Book)
    object Study : Screen("study", R.string.nav_study, Icons.Default.School)
    object Practice : Screen("practice", R.string.nav_practice, Icons.Default.RecordVoiceOver)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
}

val navItems = listOf(
    Screen.Transcript,
    Screen.History,
    Screen.Vocabulary,
    Screen.Study,
    Screen.Practice
)

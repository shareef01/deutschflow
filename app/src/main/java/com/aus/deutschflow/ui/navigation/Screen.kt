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

/**
 * The bottom bar's destinations - one per verb.
 *
 * History is not among them: it is a pane inside Library, which holds both what was
 * said and the words taken from it. Four tabs rather than five, and the archive
 * stops being two places to look for the same memory.
 */
val navItems = listOf(
    Screen.Transcript,
    Screen.Vocabulary,
    Screen.Study,
    Screen.Practice
)

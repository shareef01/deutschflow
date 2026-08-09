package com.aus.deutschflow.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.School
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Transcript : Screen("transcript", "Transcript", Icons.Default.Mic)
    object History : Screen("history", "History", Icons.Default.History)
    object Vocabulary : Screen("vocabulary", "Library", Icons.Default.Book)
    object Study : Screen("study", "Study", Icons.Default.School)
    object Practice : Screen("practice", "Practice", Icons.Default.RecordVoiceOver)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val navItems = listOf(
    Screen.Transcript,
    Screen.History,
    Screen.Vocabulary,
    Screen.Study,
    Screen.Practice
)

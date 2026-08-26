package com.aus.deutschflow.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.SegmentedTabs
import com.aus.deutschflow.ui.theme.Spacing

/**
 * Everything the app has kept, in one place: Words & Transcripts.
 */
@Composable
fun LibraryScreen(
    windowSizeClass: WindowSizeClass,
    onStartTranscript: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabLabels = listOf(
        stringResource(R.string.library_tab_words),
        stringResource(R.string.library_tab_transcripts)
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(Spacing.xs))
        SegmentedTabs(
            selectedIndex = selectedTab,
            tabs = tabLabels,
            onTabSelected = { selectedTab = it }
        )
        Spacer(modifier = Modifier.height(Spacing.xs))

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                VocabularyScreen(windowSizeClass)
            } else {
                HistoryScreen(onStartTranscript = onStartTranscript)
            }
        }
    }
}


package com.aus.deutschflow.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.SegmentTab

/**
 * Everything the app has kept, in one place.
 *
 * History and Library were two tabs over one act: History held what was said, and
 * Library held the words pulled out of what was said. One is the raw capture and
 * the other its distillate, so someone looking for "that thing about the bakery"
 * had to guess which of the two it lived in - and five tabs is the practical
 * ceiling on a phone, so the pair was also costing a slot.
 *
 * Study and Practice were the other candidate for merging and are deliberately left
 * alone: they look adjacent but test opposite skills - recall against production -
 * and folding speaking practice into a tab called Study would bury the one feature
 * that uses the microphone for feedback.
 */
@Composable
fun LibraryScreen(
    windowSizeClass: WindowSizeClass,
    onStartTranscript: () -> Unit = {}
) {
    // Saveable, so rotating the phone does not silently move you to the other pane.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            divider = {}
        ) {
            SegmentTab(
                selected = selectedTab == 0,
                label = stringResource(R.string.library_tab_words),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedTab = 0
                }
            )
            SegmentTab(
                selected = selectedTab == 1,
                label = stringResource(R.string.library_tab_transcripts),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedTab = 1
                }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                VocabularyScreen(windowSizeClass)
            } else {
                HistoryScreen(onStartTranscript = onStartTranscript)
            }
        }
    }
}

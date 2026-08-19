package com.aus.deutschflow.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * One tab in a screen's segmented control.
 *
 * Exists because a bare `Tab` inside a `TabRow` takes the row's `contentColor` for
 * *both* halves of its state. Both Study and Practice set that to the accent, so on
 * both screens both labels rendered in accent blue and only the indicator said which
 * was live - two segments that each looked active. Fixing it on one screen and not
 * the other is how a third copy gets written, so the pair lives here.
 */
@Composable
fun SegmentTab(selected: Boolean, label: String, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        selectedContentColor = MaterialTheme.colorScheme.primary,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        text = { Text(label, style = MaterialTheme.typography.labelLarge) }
    )
}

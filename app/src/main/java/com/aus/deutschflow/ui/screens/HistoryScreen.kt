package com.aus.deutschflow.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.ui.components.EmptyState
import com.aus.deutschflow.ui.components.SearchInput
import com.aus.deutschflow.ui.theme.ActionButtonHeight
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.glassSurface
import com.aus.deutschflow.ui.viewmodel.HistoryViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * HistoryScreen — past sessions as learning objects, not database rows.
 *
 * Transcripts are grouped by calendar day ("Today", "Yesterday", then the date),
 * each row shows when it happened and how much was said, and deleting confirms
 * through a snackbar with Undo rather than a blocking dialog.
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel(),
    onStartTranscript: () -> Unit = {}
) {
    val history by viewModel.transcripts.collectAsState()
    val historyQuery by viewModel.query.collectAsState()
    val hasAnyHistory by viewModel.hasAnyHistory.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.history_deleted)
    val undoLabel = stringResource(R.string.action_undo)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.md)
                .imePadding()
        ) {
        Spacer(modifier = Modifier.height(Spacing.sm))

        // Nothing to search until something has been recorded. A search field over
        // an empty list asks the user to look for what they have not made yet.
        if (hasAnyHistory) {
            // The same input the library uses - one component, one stroke.
            SearchInput(
                value = historyQuery,
                onValueChange = { viewModel.setQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.history_search_hint)
            )

            Spacer(modifier = Modifier.height(Spacing.md))
        }

        AnimatedContent(
            targetState = history.isEmpty(),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
            label = "historyContent"
        ) { isEmpty ->
            if (isEmpty) {
                EmptyState(
                    icon = Icons.Default.History,
                    message = stringResource(R.string.history_empty_title),
                    description = stringResource(R.string.history_empty_body),
                    // The way out of an empty history is to record one, so the screen
                    // offers that rather than describing the absence and stopping.
                    // Only when there is genuinely nothing: a search that matched
                    // nothing is fixed by editing the query, not by leaving.
                    action = if (!hasAnyHistory) {
                        {
                            Button(
                                onClick = onStartTranscript,
                                modifier = Modifier.height(ActionButtonHeight),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(modifier = Modifier.width(Spacing.sm))
                                Text(stringResource(R.string.history_empty_action))
                            }
                        }
                    } else null
                )
            } else {
                val groups = groupByDay(history)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    contentPadding = PaddingValues(bottom = Spacing.md)
                ) {
                    groups.forEach { (day, transcripts) ->
                        item(key = "header-${day.toEpochDay()}") {
                            HistoryDayLabel(
                                day = day,
                                modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs)
                            )
                        }
                        items(transcripts, key = { it.id }) { transcript ->
                            HistoryItem(
                                transcript = transcript,
                                onDelete = {
                                    viewModel.deleteTranscript(transcript)
                                    // Undo, not a confirmation dialog: deletion is
                                    // one tap away, and the snackbar both confirms
                                    // and reverses it.
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = deletedMessage,
                                            actionLabel = undoLabel
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.restoreTranscript(transcript)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = Spacing.md)
    )
    }
}

/**
 * Splits the newest-first list into calendar-day groups. The list arrives ordered
 * by timestamp DESC, so walking it once yields groups already in order; the label
 * is resolved at render time so it follows the app's language.
 */
private fun groupByDay(
    transcripts: List<TranscriptEntity>
): List<Pair<LocalDate, List<TranscriptEntity>>> {
    val zone = ZoneId.systemDefault()

    fun dayOf(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    return transcripts
        .groupBy { dayOf(it.timestamp) }
        .toList()
        .sortedByDescending { (day, _) -> day }
}

/** "Today", "Yesterday", or the localised date — one line per group. */
@Composable
private fun HistoryDayLabel(day: LocalDate, modifier: Modifier = Modifier) {
    val today = LocalDate.now()

    val text = when (day) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> {
            val pattern = stringResource(R.string.history_day_format)
            val format = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
            format.format(Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()))
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun HistoryItem(transcript: TranscriptEntity, onDelete: () -> Unit) {
    // The formatter and the formatted string were rebuilt on every recomposition
    // of every row; both are remembered off the inputs that produce them.
    val timePattern = stringResource(R.string.history_time_format)
    val timeFormat = remember(timePattern) { SimpleDateFormat(timePattern, Locale.getDefault()) }
    val timeString = remember(transcript.timestamp) { timeFormat.format(Date(transcript.timestamp)) }
    val wordCount = remember(transcript.fullText) {
        transcript.fullText.split(Regex("\\s+")).count { it.isNotBlank() }
    }
    val haptic = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxWidth().glassSurface()) {
        Row(
            modifier = Modifier
                .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(top = Spacing.sm)) {
                Text(
                    text = transcript.fullText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                // When it happened and how much was said - metadata, so it stays
                // quiet below the words themselves.
                Text(
                    text = stringResource(
                        R.string.history_meta,
                        timeString,
                        pluralStringResource(R.plurals.history_word_count, wordCount, wordCount)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDelete()
            }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

package com.aus.deutschflow.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.ui.components.EmptyState
import com.aus.deutschflow.ui.components.GlassmorphicCard
import com.aus.deutschflow.ui.components.SearchInput
import com.aus.deutschflow.ui.components.copyToClipboard
import com.aus.deutschflow.ui.components.rememberDateFormat
import com.aus.deutschflow.ui.components.wordCount
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.HistoryViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
    onStartTranscript: () -> Unit = {}
) {
    val history by viewModel.transcripts.collectAsState()
    val historyQuery by viewModel.query.collectAsState()
    val hasAnyHistory by viewModel.hasAnyHistory.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var viewingTranscript by remember { mutableStateOf<TranscriptEntity?>(null) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.history_deleted)
    val copiedMessage = stringResource(R.string.action_copied)
    val undoLabel = stringResource(R.string.action_undo)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.md)
                .imePadding()
        ) {
            Spacer(modifier = Modifier.height(Spacing.sm))

            if (hasAnyHistory) {
                SearchInput(
                    value = historyQuery,
                    onValueChange = { viewModel.setQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.history_search_hint)
                )
                Spacer(modifier = Modifier.height(Spacing.md))
            }

            val fade = motionDuration(Motion.STANDARD)
            AnimatedContent(
                targetState = if (isLoading) null else history.isEmpty(),
                transitionSpec = { fadeIn(tween(fade)) togetherWith fadeOut(tween(fade)) },
                label = "historyContent"
            ) { state ->
                if (state == null) {
                    HistorySkeleton()
                } else if (state) {
                    EmptyState(
                        icon = Icons.Default.History,
                        message = stringResource(R.string.history_empty_title),
                        description = stringResource(R.string.history_empty_body),
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
                    val groups = remember(history) { groupByDay(history) }
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
                                val onDelete: () -> Unit = {
                                    viewModel.deleteTranscript(transcript)
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
                                SwipeToDeleteRow(onDelete = onDelete) {
                                    HistoryItem(
                                        transcript = transcript,
                                        onOpen = { viewingTranscript = transcript },
                                        onDelete = onDelete
                                    )
                                }
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

        // Detail Dialog when viewing a past transcript
        viewingTranscript?.let { transcript ->
            TranscriptDetailDialog(
                transcript = transcript,
                onDismiss = { viewingTranscript = null },
                onSpeak = { viewModel.speak(transcript.fullText) },
                onCopy = {
                    copyToClipboard(context, transcript.fullText)
                    scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                }
            )
        }
    }
}

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

@Composable
private fun HistoryDayLabel(day: LocalDate, modifier: Modifier = Modifier) {
    val today = LocalDate.now()

    val text = when (day) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> {
            val pattern = stringResource(R.string.history_day_format)
            val format = rememberDateFormat(pattern)
            format.format(Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()))
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun HistoryItem(
    transcript: TranscriptEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val timePattern = stringResource(R.string.history_time_format)
    val timeFormat = rememberDateFormat(timePattern)
    val timeString = remember(transcript.timestamp) { timeFormat.format(Date(transcript.timestamp)) }
    val words = remember(transcript.fullText) { wordCount(transcript.fullText) }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface()
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onOpen()
            }
    ) {
        Row(
            modifier = Modifier
                .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(top = Spacing.xs)) {
                Text(
                    text = transcript.fullText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(
                        R.string.history_meta,
                        timeString,
                        pluralStringResource(R.plurals.history_word_count, words, words)
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
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun TranscriptDetailDialog(
    transcript: TranscriptEntity,
    onDismiss: () -> Unit,
    onSpeak: () -> Unit,
    onCopy: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val timePattern = stringResource(R.string.history_time_format)
    val timeFormat = rememberDateFormat(timePattern)
    val timeString = remember(transcript.timestamp) { timeFormat.format(Date(transcript.timestamp)) }
    val words = remember(transcript.fullText) { wordCount(transcript.fullText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.history_detail_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSpeak()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(R.string.transcript_listen),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCopy()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.transcript_copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(
                        R.string.history_meta,
                        timeString,
                        pluralStringResource(R.plurals.history_word_count, words, words)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                GlassmorphicCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(Spacing.md)
                ) {
                    Text(
                        text = transcript.fullText,
                        style = TranscriptTextStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDelete()
            }
            false
        }
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = Spacing.lg),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        content = { content() }
    )
}

@Composable
private fun HistorySkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        repeat(4) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HistoryRowSkeletonHeight)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    }
}


package com.aus.deutschflow.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.ui.theme.glassSurface
import com.aus.deutschflow.ui.viewmodel.HistoryViewModel
import com.aus.deutschflow.ui.components.EmptyState
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel()) {
    val history by viewModel.transcripts.collectAsState()
    val historyQuery by viewModel.query.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .imePadding()
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Standardized Search Bar
        OutlinedTextField(
            value = historyQuery,
            onValueChange = { viewModel.setQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.history_search_hint), style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedContent(
            targetState = history.isEmpty(),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
            label = "historyContent"
        ) { isEmpty ->
            if (isEmpty) {
                EmptyState(
                    icon = Icons.Default.History,
                    message = stringResource(R.string.history_empty_title),
                    description = stringResource(R.string.history_empty_body)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(history, key = { it.id }) { transcript ->
                        HistoryItem(
                            transcript = transcript,
                            onDelete = { viewModel.deleteTranscript(transcript) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItem(transcript: TranscriptEntity, onDelete: () -> Unit) {
    // Both the formatter and the formatted string were rebuilt on every
    // recomposition of every row.
    val pattern = stringResource(R.string.history_date_format)
    val dateFormat = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
    val dateString = remember(transcript.timestamp) { dateFormat.format(Date(transcript.timestamp)) }
    val haptic = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxWidth().glassSurface()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Not uppercased, not primary: the timestamp is the least
                    // important thing in the row and it was the loudest, in brand
                    // blue over the transcript it belongs to. Screen readers also
                    // spell some uppercase strings out letter by letter.
                    text = dateString,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = transcript.fullText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
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

package com.aus.deutschflow.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.ui.viewmodel.VocabularyViewModel
import com.aus.deutschflow.ui.components.EmptyState

@Composable
fun VocabularyScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: VocabularyViewModel = viewModel()
) {
    val vocabularyList by viewModel.vocabularyList.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var editingItem by remember { mutableStateOf<VocabularyEntity?>(null) }
    var selectedItem by remember { mutableStateOf<VocabularyEntity?>(null) }

    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    if (isExpanded) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f)) {
                VocabularyListContent(
                    searchQuery = searchQuery,
                    onSearchChange = { viewModel.setSearchQuery(it) },
                    vocabularyList = vocabularyList,
                    onItemClick = { selectedItem = it },
                    onEdit = { editingItem = it },
                    onDelete = { viewModel.deleteVocabulary(it) },
                    onSpeak = { viewModel.speak(it) }
                )
            }
            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                VocabularyDetailScreen(
                    item = selectedItem,
                    onClose = { selectedItem = null },
                    onSpeak = { viewModel.speak(it) }
                )
            }
        }
    } else {
        AnimatedContent(
            targetState = selectedItem != null,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
            label = "vocabContent"
        ) { isDetailVisible ->
            if (isDetailVisible) {
                VocabularyDetailScreen(
                    item = selectedItem,
                    onClose = { selectedItem = null },
                    onSpeak = { viewModel.speak(it) }
                )
            } else {
                VocabularyListContent(
                    searchQuery = searchQuery,
                    onSearchChange = { viewModel.setSearchQuery(it) },
                    vocabularyList = vocabularyList,
                    onItemClick = { selectedItem = it },
                    onEdit = { editingItem = it },
                    onDelete = { viewModel.deleteVocabulary(it) },
                    onSpeak = { viewModel.speak(it) }
                )
            }
        }
    }

    if (editingItem != null) {
        EditVocabularyDialog(
            item = editingItem!!,
            onDismiss = { editingItem = null },
            onSave = { updatedItem ->
                viewModel.updateVocabulary(updatedItem)
                editingItem = null
            }
        )
    }
}

@Composable
fun VocabularyListContent(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    vocabularyList: List<VocabularyEntity>,
    onItemClick: (VocabularyEntity) -> Unit,
    onEdit: (VocabularyEntity) -> Unit,
    onDelete: (VocabularyEntity) -> Unit,
    onSpeak: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Standardized Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search words or translations...", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedContent(
            targetState = vocabularyList.isEmpty(),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
            label = "vocabList"
        ) { isEmpty ->
            if (isEmpty) {
                EmptyState(
                    icon = Icons.Default.AutoStories,
                    message = "Your library is empty",
                    description = "Translate and save words to see them here."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(vocabularyList, key = { it.id }) { item ->
                        VocabularyItem(
                            item = item,
                            onOpen = { onItemClick(item) },
                            onEdit = { onEdit(item) },
                            onDelete = { onDelete(item) },
                            onSpeak = { onSpeak(item.germanText) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VocabularyItem(
    item: VocabularyEntity,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSpeak: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onOpen()
        }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.germanText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = item.englishTranslation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSpeak()
                }) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow, 
                        contentDescription = "Speak", 
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp)
                    )
                }
                // The edit dialog existed but nothing ever opened it - tapping a card
                // opened the detail view instead, so a typo could only be fixed by
                // deleting the word and adding it again.
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onEdit()
                }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete()
                }) {
                    Icon(
                        imageVector = Icons.Default.Delete, 
                        contentDescription = "Delete", 
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EditVocabularyDialog(
    item: VocabularyEntity,
    onDismiss: () -> Unit,
    onSave: (VocabularyEntity) -> Unit
) {
    var germanText by remember { mutableStateOf(item.germanText) }
    var translation by remember { mutableStateOf(item.englishTranslation) }
    val haptic = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Vocabulary", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = germanText,
                    onValueChange = { germanText = it },
                    label = { Text("German") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    label = { Text("Translation") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSave(item.copy(germanText = germanText, englishTranslation = translation))
            }) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

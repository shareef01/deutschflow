package com.aus.deutschflow.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.aus.deutschflow.ui.components.ErrorBanner

@Composable
fun VocabularyScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: VocabularyViewModel = viewModel()
) {
    val vocabularyList by viewModel.vocabularyList.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val ttsError by viewModel.ttsError.collectAsState()

    // Ids rather than entities, and saveable rather than remembered: VocabularyEntity
    // is not Parcelable, and rotating used to drop whichever word was open and
    // discard anything half-typed into the edit dialog.
    var editingId by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var isAdding by rememberSaveable { mutableStateOf(false) }

    val selectedItem = vocabularyList.firstOrNull { it.id == selectedId }
    val editingItem = vocabularyList.firstOrNull { it.id == editingId }

    // The model's own example when the word came from a translation, and a generated
    // one only for words typed in by hand.
    //
    // Held per word: the generator picks at random, so composing it inline would
    // reshuffle the sentence on every recomposition. Keyed on the resolved word
    // rather than on selectedId, because the list arrives a frame after a restored
    // id does - keying on the id alone left the sentence permanently blank after a
    // rotation.
    val exampleSentence = remember(selectedItem?.germanText, selectedItem?.exampleSentence) {
        selectedItem?.run {
            exampleSentence.ifBlank { viewModel.exampleFor(germanText) }
        }.orEmpty()
    }

    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    // On a compact width the detail view is a state swap inside this destination
    // rather than a destination of its own, so without this the system back gesture
    // leaves the library altogether instead of closing the word.
    BackHandler(enabled = !isExpanded && selectedItem != null) {
        selectedId = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Speak buttons sit in both the list rows and the detail pane, so the banner
        // goes above whichever of the two is currently showing.
        ErrorBanner(ttsError, modifier = Modifier.padding(horizontal = 16.dp))

        if (isExpanded) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f)) {
                    VocabularyListContent(
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        vocabularyList = vocabularyList,
                        onItemClick = { selectedId = it.id },
                        onEdit = { editingId = it.id },
                        onDelete = { viewModel.deleteVocabulary(it) },
                        onSpeak = { viewModel.speak(it) },
                        onAdd = { isAdding = true }
                    )
                }
                VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
                Column(modifier = Modifier.weight(1f)) {
                    VocabularyDetailScreen(
                        item = selectedItem,
                        exampleSentence = exampleSentence,
                        onClose = { selectedId = null },
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
                        exampleSentence = exampleSentence,
                        onClose = { selectedId = null },
                        onSpeak = { viewModel.speak(it) }
                    )
                } else {
                    VocabularyListContent(
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        vocabularyList = vocabularyList,
                        onItemClick = { selectedId = it.id },
                        onEdit = { editingId = it.id },
                        onDelete = { viewModel.deleteVocabulary(it) },
                        onSpeak = { viewModel.speak(it) },
                        onAdd = { isAdding = true }
                    )
                }
            }
        }
    }

    if (editingItem != null) {
        EditVocabularyDialog(
            item = editingItem,
            onDismiss = { editingId = null },
            onSave = { updatedItem ->
                viewModel.updateVocabulary(updatedItem)
                editingId = null
            }
        )
    }

    if (isAdding) {
        AddVocabularyDialog(
            onDismiss = { isAdding = false },
            onSave = { german, english ->
                viewModel.addVocabulary(german, english)
                isAdding = false
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
    onSpeak: (String) -> Unit,
    onAdd: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .imePadding()
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
                        // The library no longer depends on a working API key, so say so.
                        description = "Add a word with the button below, or transcribe " +
                            "speech and save the translation."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        // Clears the add button so the last row is never trapped under it.
                        contentPadding = PaddingValues(bottom = 96.dp)
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

        // The only way into the library that never touches the network.
        FloatingActionButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onAdd()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add a word")
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
    VocabularyEditorDialog(
        title = "Edit Vocabulary",
        confirmLabel = "Save Changes",
        stateKey = item.id,
        initialGerman = item.germanText,
        initialEnglish = item.englishTranslation,
        onDismiss = onDismiss,
        onSave = { german, english ->
            onSave(item.copy(germanText = german, englishTranslation = english))
        }
    )
}

@Composable
fun AddVocabularyDialog(
    onDismiss: () -> Unit,
    onSave: (german: String, english: String) -> Unit
) {
    VocabularyEditorDialog(
        title = "Add Word",
        confirmLabel = "Add to Library",
        stateKey = ADD_DIALOG_STATE_KEY,
        initialGerman = "",
        initialEnglish = "",
        onDismiss = onDismiss,
        onSave = onSave
    )
}

private const val ADD_DIALOG_STATE_KEY = "add-vocabulary"

@Composable
private fun VocabularyEditorDialog(
    title: String,
    confirmLabel: String,
    stateKey: Any,
    initialGerman: String,
    initialEnglish: String,
    onDismiss: () -> Unit,
    onSave: (german: String, english: String) -> Unit
) {
    var germanText by rememberSaveable(stateKey) { mutableStateOf(initialGerman) }
    var translation by rememberSaveable(stateKey) { mutableStateOf(initialEnglish) }
    val haptic = LocalHapticFeedback.current

    // Saving blank fields used to be allowed, which wrote two empty strings over a
    // real entry and left an unreachable, unreadable row in the library.
    val isValid = germanText.isNotBlank() && translation.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = germanText,
                    onValueChange = { germanText = it },
                    label = { Text("German") },
                    isError = germanText.isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    label = { Text("Translation") },
                    isError = translation.isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = isValid,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSave(germanText.trim(), translation.trim())
                }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

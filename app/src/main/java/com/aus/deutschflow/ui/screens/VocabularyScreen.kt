package com.aus.deutschflow.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.ui.viewmodel.VocabularyViewModel
import com.aus.deutschflow.ui.components.EmptyState
import com.aus.deutschflow.ui.components.ErrorBanner
import com.aus.deutschflow.ui.theme.AzureGlow
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.glassSurface

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

    LaunchedEffect(Unit) { viewModel.dismissTtsError() }

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
                Column(modifier = Modifier.weight(1f).padding(end = Spacing.sm)) {
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
                // The same azure hairline the navigation bar uses for its divider:
                // the one divider language in the app.
                VerticalDivider(
                    thickness = Dp.Hairline,
                    color = AzureGlow.copy(alpha = 0.15f)
                )
                Column(modifier = Modifier.weight(1f).padding(end = Spacing.sm)) {
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
                placeholder = { Text(stringResource(R.string.library_search_hint), style = MaterialTheme.typography.bodyMedium) },
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
                targetState = vocabularyList.isEmpty(),
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "vocabList"
            ) { isEmpty ->
                if (isEmpty) {
                    EmptyState(
                        icon = Icons.Default.AutoStories,
                        message = stringResource(R.string.library_empty_title),
                        // The library no longer depends on a working API key, so say so.
                        description = stringResource(R.string.library_empty_body)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        // Clears the add button so the last row is never trapped under it.
                        contentPadding = PaddingValues(bottom = Spacing.bottomActionClearance)
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
            // primary, not the default primaryContainer: this app's container navy on
            // a near-black ground made the button read as a dead rectangle.
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.library_add_word))
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

    // Glass, like the history rows: the two content lists are the same kind of
    // thing, so they sit on the same surface. A solid card here was the one
    // panel left over from the pre-glass design pass.
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
                .padding(start = Spacing.md, top = Spacing.sm, end = Spacing.sm, bottom = Spacing.sm)
                .fillMaxWidth(),
            // Top, not centre. Entries are whole sentences, so the text block is
            // often three lines tall and the controls sat stranded in the middle of
            // it, reading as though they belonged to the second line.
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = Spacing.sm, end = Spacing.sm)
            ) {
                Text(
                    text = item.germanText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = item.englishTranslation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSpeak()
            }) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.action_speak),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Edit and delete move behind a menu. Three competing targets crowded the
            // text, and the one that destroys a word without asking was the easiest
            // of them to hit by accident.
            Box {
                var menuOpen by rememberSaveable { mutableStateOf(false) }

                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.library_more_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.action_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuOpen = false
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDelete()
                        }
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
        title = stringResource(R.string.library_dialog_edit_title),
        confirmLabel = stringResource(R.string.library_dialog_edit_confirm),
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
        title = stringResource(R.string.library_dialog_add_title),
        confirmLabel = stringResource(R.string.library_dialog_add_confirm),
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
                    label = { Text(stringResource(R.string.library_field_german)) },
                    isError = germanText.isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    label = { Text(stringResource(R.string.library_field_translation)) },
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
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

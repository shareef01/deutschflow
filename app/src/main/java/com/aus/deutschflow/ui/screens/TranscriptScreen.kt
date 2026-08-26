package com.aus.deutschflow.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.service.GrammarNote
import com.aus.deutschflow.ui.components.*
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.TranscriptViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptScreen(viewModel: TranscriptViewModel = hiltViewModel()) {
    val partialText by viewModel.partialText.collectAsState()
    val finalText by viewModel.finalText.collectAsState()
    val translation by viewModel.translation.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val suggestedWords by viewModel.suggestedWords.collectAsState()
    val grammarNotes by viewModel.grammarNotes.collectAsState()
    val errorState by viewModel.errorState.collectAsState()
    val ttsError by viewModel.ttsError.collectAsState()
    val aiError by viewModel.aiError.collectAsState()
    val wordDetails by viewModel.wordDetails.collectAsState()
    val wordDetailError by viewModel.wordDetailError.collectAsState()
    val interrogatingWord by viewModel.interrogatingWord.collectAsState()
    val selectedDialect by viewModel.selectedDialect.collectAsState()
    val isFirstRun by viewModel.isFirstRun.collectAsState()
    val listeningSeconds by viewModel.listeningSeconds.collectAsState()
    val permissionDenied by viewModel.permissionDenied.collectAsState()

    val amplitude = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(viewModel) {
        viewModel.rmsLevel.collect { amplitude.floatValue = it }
    }

    val context = LocalContext.current
    val view = LocalView.current
    LaunchedEffect(finalText) {
        if (finalText.isNotBlank()) view.announceForAccessibility(finalText)
    }

    val savedMessage = stringResource(R.string.transcript_saved)
    val copiedMessage = stringResource(R.string.action_copied)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(wordDetailError) {
        wordDetailError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissWordDetailError(it)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startListening()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    OnLeavingScreen { viewModel.cancelListening() }

    Box(modifier = Modifier.fillMaxSize()) {
        TranscriptContent(
            partialText = partialText,
            finalText = finalText,
            translation = translation,
            isListening = isListening,
            isBusy = isBusy,
            suggestedWords = suggestedWords,
            grammarNotes = grammarNotes,
            loadingWord = interrogatingWord,
            errorState = errorState ?: aiError ?: ttsError,
            rmsAmplitude = amplitude,
            dialect = selectedDialect,
            isFirstRun = isFirstRun,
            listeningSeconds = listeningSeconds,
            onSelectDialect = { viewModel.selectDialect(it) },
            permissionDenied = permissionDenied,
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            },
            onStartListening = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startListening()
                } else {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStopListening = { viewModel.stopListening() },
            onWordClick = { word -> viewModel.interrogateWord(word) },
            onSpeakTranscript = { text -> viewModel.speak(text) },
            onCopyTranscript = { text ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("German Transcript", text)
                clipboard.setPrimaryClip(clip)
                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
            },
            onSave = {
                if (viewModel.saveToVocabulary(finalText, translation)) {
                    scope.launch { snackbarHostState.showSnackbar(savedMessage) }
                }
            }
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        WordDetailsBottomSheet(
            details = wordDetails,
            onDismiss = { viewModel.dismissWordDetails() },
            onSave = { details ->
                val saved = viewModel.saveWordDetails(details)
                viewModel.dismissWordDetails()
                if (saved) {
                    scope.launch { snackbarHostState.showSnackbar(savedMessage) }
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptContent(
    partialText: String,
    finalText: String,
    translation: String,
    isListening: Boolean,
    isBusy: Boolean,
    suggestedWords: List<String>,
    grammarNotes: List<GrammarNote>,
    loadingWord: String?,
    errorState: String?,
    rmsAmplitude: State<Float>,
    dialect: String,
    isFirstRun: Boolean,
    listeningSeconds: Int,
    onSelectDialect: (String) -> Unit,
    permissionDenied: Boolean,
    onOpenSettings: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onWordClick: (String) -> Unit,
    onSpeakTranscript: (String) -> Unit,
    onCopyTranscript: (String) -> Unit,
    onSave: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val scrollState = rememberScrollState()

    val hasTranscript = partialText.isNotEmpty() || finalText.isNotEmpty()
    val isIdle = !hasTranscript && !isListening && !isBusy
    val currentText = if (isListening) partialText else finalText

    Column(modifier = Modifier.fillMaxSize()) {
        // ==========================================
        // 1. TOP FIXED HEADER / DIALECT REGION
        // ==========================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs, bottom = Spacing.xs),
            contentAlignment = Alignment.Center
        ) {
            LanguageIndicator(dialect, onSelectDialect)
        }

        val fadeTop by remember {
            derivedStateOf { scrollState.value > 0 }
        }
        val fadeBottom by remember {
            derivedStateOf { scrollState.value < scrollState.maxValue }
        }

        // ==========================================
        // 2. SCROLLABLE CONTENT REGION
        // ==========================================
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .scrollFadingEdges(
                    topFadeHeight = 24.dp,
                    bottomFadeHeight = 32.dp,
                    fadeTop = fadeTop,
                    fadeBottom = fadeBottom
                )
                .verticalScroll(scrollState)
                .padding(horizontal = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(Spacing.xs))

            // Error or Permission Banner
            ErrorBanner(
                message = errorState,
                action = if (permissionDenied) {
                    {
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(R.string.action_open_settings))
                        }
                    }
                } else null
            )

            if (isIdle) {
                // Calm, instructional idle empty state
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xxl)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = AzureGlow,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    Text(
                        text = stringResource(R.string.transcript_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.transcript_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Spacing.md)
                    )
                }
            } else {
                // Live / Final Transcript Card
                TranscriptDisplayCard(
                    text = currentText,
                    isListening = isListening,
                    isBusy = isBusy,
                    listeningSeconds = listeningSeconds,
                    rmsAmplitude = rmsAmplitude,
                    onSpeak = { onSpeakTranscript(currentText) },
                    onCopy = { onCopyTranscript(currentText) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TranscriptCardMinHeight)
                )
            }

            // Results Section: Grammar Spotlight, Translation, Key Vocabulary, Save
            if (translation.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.lg))

                // Grammar Spotlight Section
                if (grammarNotes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.transcript_grammar_spotlight),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = UppercaseLabelTracking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.xs, bottom = Spacing.sm)
                    )
                    grammarNotes.forEach { note ->
                        GrammarSpotlightCard(note)
                        Spacer(modifier = Modifier.height(Spacing.sm))
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                }

                // Translation Section
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.transcript_section_translation).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = UppercaseLabelTracking,
                        modifier = Modifier.padding(bottom = Spacing.sm, start = Spacing.xs)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface()
                            .padding(Spacing.md)
                    ) {
                        Text(
                            text = translation,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (suggestedWords.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.lg))
                        Text(
                            text = stringResource(R.string.transcript_section_vocabulary).uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = UppercaseLabelTracking,
                            modifier = Modifier.padding(bottom = Spacing.sm, start = Spacing.xs)
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            suggestedWords.forEach { word ->
                                VocabularyChip(
                                    word = word,
                                    isLoading = loadingWord == word,
                                    onClick = { onWordClick(word) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))
                    PrimaryActionButton(
                        text = stringResource(R.string.transcript_save),
                        onClick = onSave,
                        icon = { Icon(Icons.Default.BookmarkAdd, contentDescription = null, tint = Color.White) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Reserved bottom space inside the scroll container so content scrolls cleanly above the dock
            Spacer(modifier = Modifier.height(Spacing.lg))
        }

        // ==========================================
        // 3. PERSISTENT DOCKED MICROPHONE ACTION REGION
        // ==========================================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs, bottom = Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isListening && !LocalReducedMotion.current) {
                        val pulseScale by rememberInfiniteTransition(label = "recordPulse").animateFloat(
                            initialValue = 1f,
                            targetValue = 1.30f,
                            animationSpec = infiniteRepeatable(
                                tween(Motion.PULSE_PERIOD, easing = Motion.Standard),
                                RepeatMode.Restart
                            ),
                            label = "pulse"
                        )
                        Box(
                            Modifier
                                .size(RecordButtonSize)
                                .scale(pulseScale)
                                .alpha(0.3f)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                    }

                    val interactionSource = remember { MutableInteractionSource() }
                    val micLabel = stringResource(
                        if (isListening) R.string.transcript_stop else R.string.transcript_start
                    )

                    Box(
                        modifier = Modifier
                            .size(RecordButtonSize)
                            .pressScale(interactionSource)
                            .clip(CircleShape)
                            .background(
                                if (isListening) {
                                    Brush.linearGradient(
                                        listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                                    )
                                } else {
                                    Brush.linearGradient(listOf(AzureGlow, primaryColor))
                                }
                            )
                            .clickable(
                                interactionSource = interactionSource,
                                indication = ripple(bounded = false)
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (isListening) onStopListening() else onStartListening()
                            }
                            .semantics {
                                role = Role.Button
                                contentDescription = micLabel
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(RecordIconSize),
                                tint = Color.White
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(
                        if (isListening) R.string.transcript_stop else R.string.transcript_idle_hint
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Human-readable recognition dialect selector.
 */
@Composable
private fun LanguageIndicator(dialect: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val label = stringResource(R.string.transcript_change_dialect)

    val dialectLabel = when (dialect) {
        "de-AT" -> stringResource(R.string.settings_dialect_at)
        "de-CH" -> stringResource(R.string.settings_dialect_ch)
        else -> stringResource(R.string.settings_dialect_de)
    }

    Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                open = true
            },
            modifier = Modifier.semantics { contentDescription = label }
        ) {
            Row(
                modifier = Modifier.padding(
                    start = Spacing.md,
                    end = Spacing.sm,
                    top = 6.dp,
                    bottom = 6.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.transcript_listening_for, dialectLabel),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            val dialects = listOf(
                "de-DE" to R.string.settings_dialect_de,
                "de-AT" to R.string.settings_dialect_at,
                "de-CH" to R.string.settings_dialect_ch
            )
            dialects.forEach { (code, nameRes) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(nameRes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (code == dialect) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(code)
                        open = false
                    },
                    trailingIcon = {
                        if (code == dialect) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            }
        }
    }
}

/**
 * Modern transcript card with live audio visualization and copy/audio action controls.
 */
@Composable
private fun TranscriptDisplayCard(
    text: String,
    isListening: Boolean,
    isBusy: Boolean,
    listeningSeconds: Int,
    rmsAmplitude: State<Float>,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val wordCount = remember(text) {
        if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    }

    GlassmorphicCard(
        modifier = modifier,
        contentPadding = PaddingValues(Spacing.md)
    ) {
        // Card Top Bar: Word count badge and action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isListening) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.transcript_listening_status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = AzureGlow
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.transcript_processing_status),
                        style = MaterialTheme.typography.labelSmall,
                        color = AzureGlow,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (text.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.history_word_count, wordCount, wordCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            if (text.isNotBlank() && !isListening) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        // Main Text Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs)
        ) {
            Text(
                text = text.ifEmpty { stringResource(R.string.transcript_placeholder) },
                style = TranscriptTextStyle,
                color = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
        }

        // Live Audio Waveform while listening
        if (isListening) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AudioWaveform(
                    amplitude = rmsAmplitude,
                    isActive = true,
                    modifier = Modifier
                        .weight(1f)
                        .height(WaveformHeight)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(
                        R.string.transcript_elapsed,
                        listeningSeconds / 60,
                        listeningSeconds % 60
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun GrammarSpotlightCard(note: GrammarNote) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = note.case.uppercase(),
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = note.phrase,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (note.explanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = note.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


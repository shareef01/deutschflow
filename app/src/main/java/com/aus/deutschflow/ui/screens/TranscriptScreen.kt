package com.aus.deutschflow.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.service.GrammarNote
import com.aus.deutschflow.ui.components.*
import com.aus.deutschflow.ui.theme.WaveformHeight
import com.aus.deutschflow.ui.theme.TranscriptCardMinHeight
import com.aus.deutschflow.ui.theme.UppercaseLabelTracking
import com.aus.deutschflow.ui.theme.TranscriptTextStyle
import com.aus.deutschflow.ui.theme.ActionButtonHeight
import com.aus.deutschflow.ui.theme.AzureGlow
import com.aus.deutschflow.ui.theme.DeutschflowTheme
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.glassSurface
import com.aus.deutschflow.ui.viewmodel.TranscriptViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

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
    val aiError by viewModel.aiError.collectAsState()
    val wordDetails by viewModel.wordDetails.collectAsState()
    val wordDetailError by viewModel.wordDetailError.collectAsState()
    val interrogatingWord by viewModel.interrogatingWord.collectAsState()
    val selectedDialect by viewModel.selectedDialect.collectAsState()
    val isFirstRun by viewModel.isFirstRun.collectAsState()

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
            errorState = errorState ?: aiError,
            rmsAmplitude = amplitude,
            dialect = selectedDialect,
            isFirstRun = isFirstRun,
            onStartListening = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startListening()
                } else {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStopListening = { viewModel.stopListening() },
            onWordClick = { word -> viewModel.interrogateWord(word) },
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
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onWordClick: (String) -> Unit,
    onSave: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val gradientBrush = Brush.linearGradient(
        colors = listOf(AzureGlow, primaryColor)
    )

    val hasTranscript = partialText.isNotEmpty() || finalText.isNotEmpty()
    val isEmpty = !hasTranscript && !isListening && !isBusy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The dialect chip has one home: directly under the header, on every state.
        // It used to live inside the empty block, which meant it moved when the
        // screen changed state and was the first thing the header sliced on scroll.
        Spacer(modifier = Modifier.height(Spacing.sm))
        LanguageIndicator(dialect)
        Spacer(modifier = Modifier.height(Spacing.md))

        // Introductory copy, once. It explains the app to someone who has never used
        // it, and after that it is a paragraph standing between them and the mic -
        // which is what pushed the primary control of the whole app below the fold.
        //
        // The 500dp minimum this block used to carry is gone with it: it reserved
        // half a screen and centred two lines in the middle of it, so the space read
        // as something failing to load rather than as breathing room.
        if (isEmpty && isFirstRun) {
            Text(
                text = stringResource(R.string.transcript_empty_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.transcript_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
        }

        // Transcript Area
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TranscriptCardMinHeight),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = MaterialTheme.shapes.extraLarge,
            elevation = CardDefaults.outlinedCardElevation(defaultElevation = 8.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Box(modifier = Modifier.padding(Spacing.lg)) {
                Text(
                    text = if (isListening) partialText else finalText.ifEmpty { "Tap the mic to transcribe..." },
                    style = TranscriptTextStyle,
                    color = if (finalText.isEmpty() && !isListening) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (isListening) {
            Spacer(modifier = Modifier.height(Spacing.md))
            AudioWaveform(
                amplitude = rmsAmplitude,
                isActive = true,
                modifier = Modifier.fillMaxWidth().height(WaveformHeight)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        // Hero Interaction
        Box(contentAlignment = Alignment.Center) {
            if (isListening) {
                val pulseScale by rememberInfiniteTransition().animateFloat(
                    initialValue = 1f, targetValue = 1.8f,
                    animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart), label = ""
                )
                Box(Modifier.size(100.dp).scale(pulseScale).alpha(0.3f).background(primaryColor, CircleShape))
            }

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(if (isListening) Brush.linearGradient(listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)) else gradientBrush)
                    .clickable { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (isListening) onStopListening() else onStartListening() 
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = Color.White
                )
            }
        }

        ErrorBanner(errorState)

        if (translation.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.xl))
            
            // Grammar Spotlight Section
            if (grammarNotes.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.transcript_grammar_spotlight),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = UppercaseLabelTracking,
                    modifier = Modifier.fillMaxWidth().padding(start = Spacing.xs)
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                grammarNotes.forEach { note ->
                    GrammarSpotlightCard(note)
                    Spacer(modifier = Modifier.height(Spacing.sm))
                }
                Spacer(modifier = Modifier.height(Spacing.lg))
            }

            // Results Section
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionLabel("Translation")
                Box(modifier = Modifier.fillMaxWidth().glassSurface().padding(Spacing.md)) {
                    Text(text = translation, style = MaterialTheme.typography.bodyLarge)
                }

                if (suggestedWords.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    SectionLabel("Vocabulary")
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().height(ActionButtonHeight),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Default.BookmarkAdd, null)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        stringResource(R.string.transcript_save),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(Spacing.bottomActionClearance))
    }
}

@Composable
fun GrammarSpotlightCard(note: GrammarNote) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                    Text(note.case.uppercase(), modifier = Modifier.padding(horizontal = Spacing.xs), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(text = note.phrase, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            if (note.explanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(text = note.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = UppercaseLabelTracking,
        modifier = Modifier.padding(bottom = Spacing.sm, start = Spacing.xs)
    )
}

@Composable
private fun LanguageIndicator(dialect: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = CircleShape
    ) {
        Text(
            text = stringResource(R.string.transcript_listening_for, dialect),
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

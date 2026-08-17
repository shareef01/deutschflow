package com.aus.deutschflow.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.AudioWaveform
import com.aus.deutschflow.ui.components.ErrorBanner
import com.aus.deutschflow.ui.components.GlassButton
import com.aus.deutschflow.ui.components.GlassmorphicCard
import com.aus.deutschflow.ui.components.OnLeavingScreen
import com.aus.deutschflow.ui.components.OracleMic
import com.aus.deutschflow.ui.components.VocabularyChip
import com.aus.deutschflow.ui.components.WordDetailsBottomSheet
import com.aus.deutschflow.ui.theme.AzureGlow
import com.aus.deutschflow.ui.theme.DeutschflowTheme
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.glassSurface
import com.aus.deutschflow.ui.viewmodel.TranscriptViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * TranscriptScreen — the app's core screen.
 *
 * Three states, each with one job:
 * - EMPTY: explain what will happen (title, one body line, the German the app
 *   listens for, and the mic with "Tap to start").
 * - RECORDING: the transcript card streams the partial text, a live waveform and
 *   a recording clock make "it is listening" obvious without a theatre.
 * - RESULT: the German dominates (headline weight); the translation, the tappable
 *   vocabulary and the one Save action follow below it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptScreen(viewModel: TranscriptViewModel = viewModel()) {
    val partialText by viewModel.partialText.collectAsState()
    val finalText by viewModel.finalText.collectAsState()
    val translation by viewModel.translation.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val suggestedWords by viewModel.suggestedWords.collectAsState()
    val errorState by viewModel.errorState.collectAsState()
    val aiError by viewModel.aiError.collectAsState()
    val wordDetails by viewModel.wordDetails.collectAsState()
    val wordDetailError by viewModel.wordDetailError.collectAsState()
    // Which word's interrogation is in flight, so the right chip shows its spinner.
    // Owned by the ViewModel, which is what cancels and replaces the request: a copy
    // remembered here could only ever be an echo of that, kept in step by an effect.
    val interrogatingWord by viewModel.interrogatingWord.collectAsState()
    val selectedDialect by viewModel.selectedDialect.collectAsState()

    // The input level is read in a draw phase, never into composition: a
    // MutableFloatState mutation costs zero recompositions, and the waveform's
    // Canvas reads it per frame. (Same pattern as PracticeScreen.)
    val amplitude = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(viewModel) {
        viewModel.rmsLevel.collect { amplitude.floatValue = it }
    }

    val context = LocalContext.current

    // Saving used to be silent: the word landed in the library and the button
    // gave nothing back, which reads as "nothing happened". One confirmation for
    // the one write the screen makes. Resolved through stringResource so a
    // configuration change cannot leave it stale.
    val savedMessage = stringResource(R.string.transcript_saved)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A failed interrogation surfaces through the same snackbar as a save, rather
    // than through the recording banner that has nothing to do with it.
    //
    // The message is handed back when it is cleared, so a failure that has already been
    // superseded is left alone: showSnackbar suspends for seconds, and a chip tapped
    // inside that window starts a request whose answer must survive this line.
    LaunchedEffect(wordDetailError) {
        wordDetailError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissWordDetailError(it)
        }
    }

    // Permission Mandate: Runtime Authorization
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startListening()
        } else {
            // Android stops showing this dialog after the second refusal, so without
            // an answer here the mic button is a control that does nothing, forever,
            // and never says why.
            viewModel.onPermissionDenied()
        }
    }

    // Without this the recognizer keeps the microphone open after the user moves to
    // another tab: this screen's ViewModel is kept alive by the saved back stack entry.
    OnLeavingScreen { viewModel.cancelListening() }

    Box(modifier = Modifier.fillMaxSize()) {
        TranscriptContent(
            partialText = partialText,
            finalText = finalText,
            translation = translation,
            isListening = isListening,
            isBusy = isBusy,
            suggestedWords = suggestedWords,
            loadingWord = interrogatingWord,
            errorState = errorState ?: aiError,
            rmsAmplitude = amplitude,
            dialect = selectedDialect,
            onStartListening = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startListening()
                } else {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStopListening = { viewModel.stopListening() },
            onWordClick = { word -> viewModel.interrogateWord(word) },
            // Only on an accepted save: the ViewModel rejects a blank side, and
            // confirming one anyway would be the screen's own report of a write that
            // never happened.
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

        // The interrogation result. It sits above the snackbar and closes on dismiss
        // or after a save.
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
    loadingWord: String?,
    errorState: String?,
    rmsAmplitude: State<Float>,
    dialect: String,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onWordClick: (String) -> Unit,
    onSave: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    val hasTranscript = partialText.isNotEmpty() || finalText.isNotEmpty()
    val isEmpty = !hasTranscript && !isListening && !isBusy

    // The recording clock, in the screen's own time. Restarted per session.
    var recordingSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(isListening) {
        if (isListening) {
            recordingSeconds = 0
            while (isListening) {
                delay(1_000)
                recordingSeconds++
            }
        }
    }
    val duration =
        String.format(Locale.ROOT, "%d:%02d", recordingSeconds / 60, recordingSeconds % 60)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isEmpty) {
            // -------- EMPTY: explain, then invite. --------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LanguageIndicator(dialect)

                Spacer(modifier = Modifier.height(Spacing.lg))

                Text(
                    text = stringResource(R.string.transcript_empty_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                Text(
                    text = stringResource(R.string.transcript_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.85f)
                )

                Spacer(modifier = Modifier.height(Spacing.xl))

                ErrorBanner(errorState)

                OracleMic(
                    icon = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.transcript_start_recording),
                    isListening = false,
                    isBusy = false,
                    onClick = onStartListening,
                    controlSize = 152.dp,
                    discSize = 128.dp,
                    iconSize = 48.dp
                )

                Spacer(modifier = Modifier.height(Spacing.md))

                Text(
                    text = stringResource(R.string.transcript_hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // -------- RECORDING / RESULT: the transcript leads. --------
            TranscriptCard(
                partialText = partialText,
                finalText = finalText,
                isListening = isListening,
                isBusy = isBusy,
                rmsAmplitude = rmsAmplitude,
                duration = duration
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            ErrorBanner(errorState)

            // Everything below the transcript shares what the card leaves, and
            // scrolls once a translation arrives and there is more of it than there
            // is room.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(Spacing.sm))

                OracleMic(
                    icon = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = stringResource(
                        if (isListening) R.string.transcript_stop_recording
                        else R.string.transcript_start_recording
                    ),
                    isListening = isListening,
                    isBusy = isBusy,
                    onClick = { if (isListening) onStopListening() else onStartListening() }
                )

                if (isBusy) {
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.transcript_transcribing),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.lg))

                // -------- Translation, vocabulary, and the one save action. --------
                if (translation.isNotEmpty()) {
                    // LocalClipboard, not the deprecated LocalClipboardManager. The
                    // replacement is suspend-based, so the copy runs in a scope rather
                    // than inline - it can touch the system clipboard service.
                    val clipboard = LocalClipboard.current
                    val scope = rememberCoroutineScope()

                    Column(modifier = Modifier.fillMaxWidth()) {
                        SectionLabel(stringResource(R.string.transcript_translation)) {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("translation", translation))
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().glassSurface()) {
                            Text(
                                text = translation,
                                modifier = Modifier.padding(Spacing.md),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (suggestedWords.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(Spacing.lg))
                            Text(
                                text = stringResource(R.string.transcript_vocabulary),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                // Interactive now: each word interrogates the model on
                                // tap, and shows a spinner while it answers.
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

                        GlassButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSave()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Text(
                                text = stringResource(R.string.transcript_save),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The transcript itself: the German at headline weight (what is being learned
 * dominates), a copy affordance for the finished result, and — while recording —
 * the live waveform and the clock that say "listening" without a word of copy.
 */
@Composable
private fun TranscriptCard(
    partialText: String,
    finalText: String,
    isListening: Boolean,
    isBusy: Boolean,
    rmsAmplitude: State<Float>,
    duration: String
) {
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val hasTranscript = partialText.isNotEmpty() || finalText.isNotEmpty()

    GlassmorphicCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp),
        contentPadding = PaddingValues(Spacing.lg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = when {
                    hasTranscript -> if (isListening) partialText else finalText
                    else -> stringResource(R.string.transcript_placeholder)
                },
                modifier = Modifier.weight(1f),
                style = if (hasTranscript) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = if (hasTranscript) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            if (finalText.isNotEmpty() && !isListening) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("transcript", finalText))
                            )
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (isListening) {
            Spacer(modifier = Modifier.height(Spacing.md))
            AudioWaveform(
                amplitude = rmsAmplitude,
                isActive = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            RecordingRow(duration)
        }
    }
}

/** The pulsing dot, "Listening…" and the running clock — the recording indicator. */
@Composable
private fun RecordingRow(duration: String) {
    val transition = rememberInfiniteTransition(label = "recording-row")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recording-dot"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(AzureGlow, CircleShape)
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = stringResource(R.string.transcript_listening),
            style = MaterialTheme.typography.labelLarge,
            color = AzureGlow
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = duration,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "German · de-AT": which language the recogniser is listening for. */
@Composable
private fun LanguageIndicator(dialect: String) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.shapes.small
            )
            .padding(horizontal = Spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(MaterialTheme.colorScheme.secondary, CircleShape)
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = stringResource(R.string.transcript_language, dialect),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/** A section heading with an optional trailing action (the copy affordance). */
@Composable
private fun SectionLabel(
    label: String,
    onTrailing: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        if (onTrailing != null) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTrailing()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TranscriptScreenPreview() {
    DeutschflowTheme {
        TranscriptContent(
            partialText = "Hallo, wie geht es dir?",
            finalText = "",
            translation = "Hello, how are you?",
            isListening = false,
            isBusy = false,
            suggestedWords = listOf("Hallo", "Deutsch", "Lernen"),
            loadingWord = null,
            errorState = "Didn't catch that. Try speaking again, a little slower.",
            rmsAmplitude = remember { mutableFloatStateOf(0.6f) },
            dialect = "de-DE",
            onStartListening = {},
            onStopListening = {},
            onWordClick = {},
            onSave = {}
        )
    }
}

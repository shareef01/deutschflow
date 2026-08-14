package com.aus.deutschflow.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.ErrorBanner
import com.aus.deutschflow.ui.components.OracleMic
import com.aus.deutschflow.ui.theme.ActionButtonHeight
import com.aus.deutschflow.ui.theme.pressScale
import com.aus.deutschflow.ui.theme.rememberPressSource
import com.aus.deutschflow.ui.theme.GlassFillRaised
import com.aus.deutschflow.ui.theme.glassSurface
import com.aus.deutschflow.ui.theme.PillShape
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.components.OnLeavingScreen
import com.aus.deutschflow.ui.theme.DeutschflowTheme
import com.aus.deutschflow.ui.viewmodel.TranscriptViewModel

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

    val context = LocalContext.current

    // Saving used to be silent: the word landed in the library and the button
    // gave nothing back, which reads as "nothing happened". One confirmation for
    // the one write the screen makes.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
            errorState = errorState ?: aiError,
            onStartListening = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startListening()
                } else {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStopListening = { viewModel.stopListening() },
            onSave = {
                viewModel.saveToVocabulary(finalText, translation)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.transcript_saved)
                    )
                }
            }
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
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
    errorState: String?,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onSave: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.md)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val hasTranscript = partialText.isNotEmpty() || finalText.isNotEmpty()

        if (hasTranscript) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .glassSurface()
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
                Text(
                    text = if (isListening) partialText else finalText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        }

        // Everything below the transcript shares what the card leaves, and scrolls
        // once a translation arrives and there is more of it than there is room.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

        Spacer(modifier = Modifier.height(Spacing.lg))

        ErrorBanner(errorState)

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

        Spacer(modifier = Modifier.height(Spacing.md))

        Text(
            text = stringResource(
                when {
                    isBusy -> R.string.transcript_transcribing
                    isListening -> R.string.transcript_listening
                    else -> R.string.transcript_hint
                }
            ),
            style = MaterialTheme.typography.labelLarge,
            color = if (isListening) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        // Translation Section
        if (translation.isNotEmpty()) {
            // LocalClipboard, not the deprecated LocalClipboardManager. The
            // replacement is suspend-based, so the copy runs in a scope rather than
            // inline - it can touch the system clipboard service.
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.transcript_translation),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("translation", translation))
                            )
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy, 
                            contentDescription = stringResource(R.string.action_copy), 
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                Box(modifier = Modifier.fillMaxWidth().glassSurface()) {
                    Text(
                        text = translation,
                        // On the spacing scale, and no inline fontSize override: the
                        // style already carries a size, and setting both meant this
                        // one block ignored the type scale everything else follows.
                        modifier = Modifier.padding(Spacing.md),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                if (suggestedWords.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    Text(
                        text = stringResource(R.string.transcript_vocabulary),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        // Rendered as labels, not chips: there is no per-word
                        // translation to save, so nothing here is tappable.
                        suggestedWords.forEach { word ->
                            Box(
                                modifier = Modifier.glassSurface(
                                    shape = MaterialTheme.shapes.small,
                                    fill = GlassFillRaised
                                )
                            ) {
                                Text(
                                    text = word,
                                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.lg))

                val saveSource = rememberPressSource()
                Button(
                    interactionSource = saveSource,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSave()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ActionButtonHeight)
                        .pressScale(saveSource),
                    shape = PillShape
                ) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.transcript_save), style = MaterialTheme.typography.labelLarge)
                }
            }
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
            errorState = "Didn't catch that. Try speaking again, a little slower.",
            onStartListening = {},
            onStopListening = {},
            onSave = {}
        )
    }
}

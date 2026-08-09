package com.aus.deutschflow.ui.screens

import android.Manifest
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
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

    // Permission Mandate: Runtime Authorization
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startListening()
        }
    }

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
        onSave = { viewModel.saveToVocabulary(finalText, translation) }
    )
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
    val gradientBrush = Brush.linearGradient(
        colors = listOf(Color(0xFF00E5FF), primaryColor)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Refactored: Premium OutlinedCard
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 250.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.outlinedCardElevation(defaultElevation = 8.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Box(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = if (isListening) partialText else finalText.ifEmpty { "Tap the mic to transcribe..." },
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                    color = if (finalText.isEmpty() && !isListening) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Error Surfacing: Escalation Directive
        AnimatedVisibility(
            visible = errorState != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = errorState ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Hero Element: Gradient Pulse Microphone
        Box(contentAlignment = Alignment.Center) {
            if (isListening) {
                val pulseTransition = rememberInfiniteTransition(label = "pulse")
                val pulseScale by pulseTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.8f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "pulseScale"
                )
                val pulseAlpha by pulseTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "pulseAlpha"
                )
                
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(pulseScale)
                        .alpha(pulseAlpha)
                        .background(primaryColor, CircleShape)
                )
            }

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        if (isListening) Brush.linearGradient(listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer))
                        else gradientBrush
                    )
                    .clickable(enabled = !isBusy) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (isListening) onStopListening() else onStartListening()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        // The app's primary control: without this a screen reader
                        // announces nothing at all for it.
                        contentDescription = if (isListening) "Stop recording" else "Start recording",
                        modifier = Modifier.size(40.dp),
                        tint = Color.White
                    )
                }
            }
        }

        if (isBusy) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Transcribing…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Translation Section
        if (translation.isNotEmpty()) {
            val clipboardManager = LocalClipboardManager.current
            
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Translation",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        clipboardManager.setText(AnnotatedString(translation)) 
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy, 
                            contentDescription = "Copy", 
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                ) {
                    Text(
                        text = translation,
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 18.sp
                    )
                }

                if (suggestedWords.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Vocabulary",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Rendered as labels, not chips: there is no per-word
                        // translation to save, so nothing here is tappable.
                        suggestedWords.forEach { word ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                            ) {
                                Text(
                                    text = word,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSave()
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save to Library", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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

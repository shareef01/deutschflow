package com.aus.deutschflow.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.ErrorBanner
import com.aus.deutschflow.ui.components.OnLeavingScreen
import com.aus.deutschflow.ui.viewmodel.PracticeFeedback
import com.aus.deutschflow.ui.viewmodel.PracticeViewModel

@Composable
fun PracticeScreen(viewModel: PracticeViewModel = viewModel()) {
    val targetSentence by viewModel.targetSentence.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val spokenText by viewModel.finalText.collectAsState()
    val wordResults by viewModel.wordResults.collectAsState()
    val errorState by viewModel.errorState.collectAsState()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val gradientBrush = Brush.linearGradient(
        colors = listOf(Color(0xFF00E5FF), primaryColor)
    )

    // The level decides the colour; the resource decides the words. Deciding the
    // colour from the words is what broke the moment they were translated.
    val isPositive = feedback == PracticeFeedback.PERFECT
    val feedbackText = when (feedback) {
        PracticeFeedback.NONE -> null
        PracticeFeedback.PERFECT -> stringResource(R.string.practice_feedback_perfect)
        PracticeFeedback.GOOD -> stringResource(R.string.practice_feedback_good)
        PracticeFeedback.KEEP_GOING -> stringResource(R.string.practice_feedback_keep_going)
    }

    // Permission Mandate: Runtime Authorization
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startPractice()
        } else {
            // Android stops showing this dialog after the second refusal, so without
            // an answer here the Speak button is a control that does nothing, forever,
            // and never says why.
            viewModel.onPermissionDenied()
        }
    }

    // Without this the recognizer keeps the microphone open after the user moves to
    // another tab: this screen's ViewModel is kept alive by the saved back stack entry.
    OnLeavingScreen { viewModel.cancelListening() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.practice_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.outlinedCardElevation(defaultElevation = 6.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val textStyle = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.3f),
                        offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                        blurRadius = 4f
                    )
                )

                if (wordResults.isEmpty()) {
                    Text(
                        text = targetSentence,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    val annotatedString = buildAnnotatedString {
                        wordResults.forEach { result ->
                            withStyle(style = SpanStyle(
                                color = if (result.isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )) {
                                append(result.word + " ")
                            }
                        }
                    }
                    Text(
                        text = annotatedString,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                FilledTonalButton(
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.speak(targetSentence) 
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.practice_listen), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        ErrorBanner(errorState)

        AnimatedVisibility(
            visible = feedback != PracticeFeedback.NONE,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                color = if (isPositive) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    if (isPositive) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                )
            ) {
                Text(
                    text = feedbackText.orEmpty(),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = if (isPositive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        OutlinedCard(
            // heightIn, not height: long recognised sentences were clipped, and worse
            // at large font scales.
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.outlinedCardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                } else {
                    AnimatedContent(
                        targetState = spokenText,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "spokenTextAnim"
                    ) { text ->
                        Text(
                            text = text.ifEmpty { stringResource(R.string.practice_waiting) },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            lineHeight = 26.sp
                        )
                    }
                }
            }
        }

        // No weight() here: this Column scrolls, so its main-axis maximum is
        // infinite and a weighted spacer collapses to zero height.
        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (isListening) {
                        viewModel.stopPractice()
                    } else {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.startPractice()
                        } else {
                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                enabled = !isProcessing,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                ),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(20.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isListening) Brush.linearGradient(listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)) 
                            else gradientBrush, 
                            RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic, 
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(if (isListening) R.string.practice_evaluate else R.string.practice_speak), 
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }
                }
            }
            
            OutlinedButton(
                onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.nextSentence() 
                },
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateNext, 
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.practice_next), 
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.GlassmorphicCard
import com.aus.deutschflow.ui.components.PrimaryActionButton
import com.aus.deutschflow.ui.components.SecondaryActionButton
import com.aus.deutschflow.ui.components.SegmentedTabs
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.PracticeFeedback
import com.aus.deutschflow.ui.viewmodel.PracticeViewModel
import com.aus.deutschflow.ui.viewmodel.RoleplayViewModel

@Composable
fun PracticeScreen(
    viewModel: PracticeViewModel = hiltViewModel(),
    roleplayViewModel: RoleplayViewModel = hiltViewModel()
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabLabels = listOf(
        stringResource(R.string.practice_tab),
        stringResource(R.string.roleplay_tab)
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(Spacing.xs))
        SegmentedTabs(
            selectedIndex = selectedTab,
            tabs = tabLabels,
            onTabSelected = { selectedTab = it }
        )
        Spacer(modifier = Modifier.height(Spacing.xs))

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                ShadowingMode(viewModel)
            } else {
                RoleplayScreen(viewModel = roleplayViewModel)
            }
        }
    }
}

@Composable
fun ShadowingMode(viewModel: PracticeViewModel) {
    val targetSentence by viewModel.targetSentence.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val spokenText by viewModel.finalText.collectAsState()
    val wordResults by viewModel.wordResults.collectAsState()
    val errorState by viewModel.errorState.collectAsState()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.primary

    val hasResult = feedback != PracticeFeedback.NONE
    val isPositive = feedback == PracticeFeedback.PERFECT || feedback == PracticeFeedback.GOOD
    val feedbackText = when (feedback) {
        PracticeFeedback.NONE -> null
        PracticeFeedback.PERFECT -> stringResource(R.string.practice_feedback_perfect)
        PracticeFeedback.GOOD -> stringResource(R.string.practice_feedback_good)
        PracticeFeedback.KEEP_GOING -> stringResource(R.string.practice_feedback_keep_going)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startPractice()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .then(if (hasResult) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (feedback == PracticeFeedback.NONE) {
            Text(
                text = stringResource(R.string.practice_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = Spacing.xs)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xs))

        // Target German Sentence Card
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.lg)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (wordResults.isEmpty()) {
                    Text(
                        text = targetSentence,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                } else {
                    val annotatedString = buildAnnotatedString {
                        wordResults.forEach { result ->
                            withStyle(
                                style = SpanStyle(
                                    color = if (result.isCorrect) TertiaryGreen else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append(result.word + " ")
                            }
                        }
                    }
                    Text(
                        text = annotatedString,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                SecondaryActionButton(
                    text = stringResource(R.string.practice_listen_repeat),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.speak(targetSentence)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // Feedback Banner
        AnimatedVisibility(
            visible = feedbackText != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.sm),
                color = if (isPositive) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(
                    1.dp,
                    if (isPositive) TertiaryGreen.copy(alpha = 0.4f) else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                )
            ) {
                Text(
                    text = feedbackText ?: "",
                    modifier = Modifier.padding(Spacing.md),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isPositive) TertiaryGreen else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Error message if any
        AnimatedVisibility(
            visible = errorState != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .padding(bottom = Spacing.sm)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f), MaterialTheme.shapes.small)
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = errorState ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Real-time Spoken Text Card
        GlassmorphicCard(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasResult) Modifier.height(PracticeResultMinHeight) else Modifier.weight(1f)),
            contentPadding = PaddingValues(Spacing.md)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = spokenText,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "spokenTextAnim"
                ) { text ->
                    Text(
                        text = text.ifEmpty { stringResource(R.string.practice_waiting) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // Action Buttons Row: Speak / Next
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            PrimaryActionButton(
                text = stringResource(
                    if (isListening) R.string.practice_evaluate else R.string.practice_speak
                ),
                icon = {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                },
                gradientColors = if (isListening) {
                    listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                } else {
                    listOf(AzureGlow, primaryColor)
                },
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
                modifier = Modifier.weight(1f)
            )

            SecondaryActionButton(
                text = stringResource(R.string.practice_next),
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.nextSentence()
                },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
    }
}


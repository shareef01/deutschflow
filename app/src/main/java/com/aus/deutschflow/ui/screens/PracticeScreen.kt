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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.SegmentTab
import com.aus.deutschflow.ui.theme.AzureGlow
import com.aus.deutschflow.ui.theme.ActionButtonHeight
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.viewmodel.PracticeFeedback
import com.aus.deutschflow.ui.viewmodel.PracticeViewModel
import com.aus.deutschflow.ui.viewmodel.RoleplayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    viewModel: PracticeViewModel = hiltViewModel(),
    roleplayViewModel: RoleplayViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            divider = {}
        ) {
            SegmentTab(
                selected = selectedTab == 0,
                label = stringResource(R.string.practice_tab),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedTab = 0
                }
            )
            SegmentTab(
                selected = selectedTab == 1,
                label = stringResource(R.string.roleplay_tab),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedTab = 1
                }
            )
        }

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
    val gradientBrush = Brush.linearGradient(
        colors = listOf(AzureGlow, primaryColor)
    )

    val isPositive = feedback == PracticeFeedback.PERFECT
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
            .padding(Spacing.md)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(Spacing.sm))

        // Until the first attempt is scored. It tells you what to do, and once you
        // have done it the instruction is a paragraph between you and the sentence
        // you are practising.
        if (feedback == PracticeFeedback.NONE) {
            Text(
                text = stringResource(R.string.practice_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(Spacing.lg))
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = MaterialTheme.shapes.extraLarge,
            elevation = CardDefaults.outlinedCardElevation(defaultElevation = 6.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(Spacing.lg),
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
                
                Spacer(modifier = Modifier.height(Spacing.lg))
                
                FilledTonalButton(
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.speak(targetSentence) 
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        stringResource(R.string.practice_listen_repeat),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        AnimatedVisibility(
            visible = errorState != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .padding(bottom = Spacing.md)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f), MaterialTheme.shapes.small)
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = errorState ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        AnimatedVisibility(
            visible = feedbackText != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.lg),
                color = if (isPositive) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, if (isPositive) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
            ) {
                Text(
                    text = feedbackText ?: "",
                    modifier = Modifier.padding(Spacing.md),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = if (isPositive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            shape = MaterialTheme.shapes.extraLarge,
            elevation = CardDefaults.outlinedCardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = spokenText,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "spokenTextAnim"
                ) { text ->
                    Text(
                        text = text.ifEmpty { "Waiting for your speech..." },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(Spacing.xl))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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
                modifier = Modifier
                    .weight(1f)
                    .height(ActionButtonHeight),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp),
                shape = MaterialTheme.shapes.large,
                // Flat. The container is transparent, so a 6dp shadow was being cast
                // for a surface that is not drawn - which is what put a hard-edged
                // lighter block inside the button's rounded shape.
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // The gradient lives inside the button and is clipped to its
                        // shape. On the outer modifier it was painted around the
                        // surface rather than as it, so nothing bounded it.
                        .clip(MaterialTheme.shapes.large)
                        .background(
                            if (isListening) {
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.error,
                                        MaterialTheme.colorScheme.errorContainer
                                    )
                                )
                            } else {
                                gradientBrush
                            }
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
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            text = stringResource(
                                if (isListening) R.string.practice_evaluate else R.string.practice_speak
                            ),
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
                modifier = Modifier.weight(1f).height(ActionButtonHeight),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateNext, 
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.practice_next),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

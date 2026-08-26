package com.aus.deutschflow.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.service.ReviewQuality
import com.aus.deutschflow.ui.components.*
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.StudyViewModel

@Composable
fun StudyScreen(viewModel: StudyViewModel = hiltViewModel()) {
    var selectedTab by rememberSaveable { mutableIntStateOf(1) } // Default to Flashcards
    val tabLabels = listOf(
        stringResource(R.string.dashboard_tab),
        stringResource(R.string.dashboard_flashcards_tab)
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
                DashboardScreen(onStartReview = { selectedTab = 1 })
            } else {
                StudySessionContent(
                    viewModel = viewModel,
                    onNavigateToDashboard = { selectedTab = 0 }
                )
            }
        }
    }
}

@Composable
fun StudySessionContent(
    viewModel: StudyViewModel,
    onNavigateToDashboard: () -> Unit
) {
    val studyList by viewModel.studyList.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val isFlipped by viewModel.isFlipped.collectAsState()
    val hasLoaded by viewModel.hasLoaded.collectAsState()
    val allWordsCount by viewModel.allWordsCount.collectAsState()
    val sessionReviewedCount by viewModel.sessionReviewedCount.collectAsState()
    val ttsError by viewModel.ttsError.collectAsState()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        viewModel.dismissTtsError()
        viewModel.startSession()
    }

    if (!hasLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    if (allWordsCount == 0) {
        EmptyState(
            icon = Icons.Default.School,
            message = stringResource(R.string.study_empty_title),
            description = stringResource(R.string.study_empty_body)
        )
        return
    }

    // Celebratory Session Complete View
    if (studyList.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            contentAlignment = Alignment.Center
        ) {
            GlassmorphicCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(Spacing.xl)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    Text(
                        text = stringResource(R.string.study_completed_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.study_completed_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.xl))
                    PrimaryActionButton(
                        text = stringResource(R.string.study_completed_action),
                        onClick = onNavigateToDashboard,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    SecondaryActionButton(
                        text = stringResource(R.string.study_completed_restart),
                        icon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { viewModel.restartSession() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        return
    }

    val safeIndex = currentIndex.coerceIn(studyList.indices)
    val currentItem = studyList[safeIndex]

    LaunchedEffect(currentIndex, currentItem.id) {
        viewModel.autoPlay(currentItem.germanText)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ErrorBanner(ttsError)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.study_session),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = pluralStringResource(R.plurals.study_remaining, studyList.size, studyList.size),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        val rotation by animateFloatAsState(
            targetValue = if (isFlipped) 180f else 0f,
            animationSpec = tween(durationMillis = motionDuration(Motion.DELIBERATE), easing = FastOutSlowInEasing),
            label = "cardFlip"
        )

        // 3D Flip Flashcard Container
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = Spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp, max = 400.dp)
                    .graphicsLayer {
                        rotationY = rotation
                        cameraDistance = 14f * density
                    }
                    .glassSurface(shape = MaterialTheme.shapes.extraLarge)
                    .clickable(
                        onClickLabel = stringResource(
                            if (isFlipped) R.string.study_show_german else R.string.study_show_translation
                        )
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.flipCard()
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    if (rotation <= 90f || rotation >= 270f) {
                        // Card Front: German Word
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (currentItem.article != "none" && currentItem.article.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = currentItem.article.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(Spacing.sm))
                            }
                            Text(
                                text = currentItem.germanText,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(Spacing.md))
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.speak(currentItem.germanText)
                                },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = stringResource(R.string.action_speak),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text(
                                text = stringResource(R.string.study_tap_to_flip),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // Card Back: English Meaning & Details
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { rotationY = 180f }
                        ) {
                            Text(
                                text = stringResource(R.string.library_field_translation).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            Text(
                                text = currentItem.englishTranslation,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            if (currentItem.plural.isNotBlank()) {
                                Spacer(modifier = Modifier.height(Spacing.md))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.word_sheet_plural, currentItem.plural),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                                    )
                                }
                            }
                            if (currentItem.exampleSentence.isNotBlank()) {
                                Spacer(modifier = Modifier.height(Spacing.md))
                                Text(
                                    text = "„${currentItem.exampleSentence}“",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // 4-Tier SRS Spaced Repetition Quality Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            StudyFeedbackButton(
                label = stringResource(R.string.study_again),
                glow = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.submitReview(ReviewQuality.AGAIN)
                }
            )
            StudyFeedbackButton(
                label = stringResource(R.string.study_hard),
                glow = WarningAmber,
                modifier = Modifier.weight(1f),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.submitReview(ReviewQuality.HARD)
                }
            )
            StudyFeedbackButton(
                label = stringResource(R.string.study_good),
                glow = AzureDeep,
                modifier = Modifier.weight(1f),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.submitReview(ReviewQuality.GOOD)
                }
            )
            StudyFeedbackButton(
                label = stringResource(R.string.study_easy),
                glow = TertiaryGreen,
                modifier = Modifier.weight(1f),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.submitReview(ReviewQuality.EASY)
                }
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        // Skip Card button
        TextButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            viewModel.skipCard()
        }) {
            Text(
                text = stringResource(R.string.study_skip),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xs))
    }
}

@Composable
private fun StudyFeedbackButton(
    label: String,
    glow: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        glow = glow
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}


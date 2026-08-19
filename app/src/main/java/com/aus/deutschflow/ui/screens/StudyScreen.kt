package com.aus.deutschflow.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.service.ReviewQuality
import com.aus.deutschflow.ui.components.SegmentTab
import com.aus.deutschflow.ui.components.EmptyState
import com.aus.deutschflow.ui.components.ErrorBanner
import com.aus.deutschflow.ui.components.GlassButton
import com.aus.deutschflow.ui.theme.motionDuration
import com.aus.deutschflow.ui.theme.Motion
import com.aus.deutschflow.ui.theme.AzureDeep
import com.aus.deutschflow.ui.theme.OnSurfaceMuted
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.TertiaryGreen
import com.aus.deutschflow.ui.theme.WarningAmber
import com.aus.deutschflow.ui.theme.glassSurface
import com.aus.deutschflow.ui.viewmodel.StudyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(viewModel: StudyViewModel = hiltViewModel()) {
    var selectedTab by remember { mutableIntStateOf(1) } // Default to Study session for continuity
    val haptic = LocalHapticFeedback.current

    Column(modifier = Modifier.fillMaxSize()) {
        // `contentColor` is what a Tab falls back to for *both* halves of its state,
        // so setting it to primary painted the unselected tab in the accent too -
        // both labels read as active and only the indicator said otherwise. Each Tab
        // now names its own pair: accent when selected, muted when not.
        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            divider = {}
        ) {
            SegmentTab(
                selected = selectedTab == 0,
                label = stringResource(R.string.dashboard_tab),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedTab = 0
                }
            )
            SegmentTab(
                selected = selectedTab == 1,
                label = stringResource(R.string.dashboard_flashcards_tab),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedTab = 1
                }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                DashboardScreen()
            } else {
                StudySessionContent(viewModel)
            }
        }
    }
}

@Composable
fun StudySessionContent(viewModel: StudyViewModel) {
    val studyList by viewModel.studyList.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val isFlipped by viewModel.isFlipped.collectAsState()
    val hasLoaded by viewModel.hasLoaded.collectAsState()
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

    if (studyList.isEmpty()) {
        EmptyState(
            icon = Icons.Default.School,
            message = stringResource(R.string.study_empty_title),
            description = stringResource(R.string.study_empty_body)
        )
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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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
                color = MaterialTheme.colorScheme.onSurface
            )
            // Show how many cards are left in the current due queue
            val remaining = studyList.size
            Text(
                text = pluralStringResource(R.plurals.study_remaining, remaining, remaining),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        val rotation by animateFloatAsState(
            targetValue = if (isFlipped) 180f else 0f,
            animationSpec = tween(durationMillis = motionDuration(Motion.DELIBERATE), easing = FastOutSlowInEasing),
            label = "cardFlip"
        )

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 420.dp)
                    .graphicsLayer {
                        rotationY = rotation
                        cameraDistance = 12f * density
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    if (rotation <= 90f || rotation >= 270f) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.library_field_german),
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceMuted
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            Text(
                                text = currentItem.germanText,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(Spacing.md))
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.speak(currentItem.germanText)
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = stringResource(R.string.action_speak),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.study_tap_to_flip),
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceMuted
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.graphicsLayer { rotationY = 180f }.padding(24.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.library_field_translation),
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceMuted
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            Text(
                                text = currentItem.englishTranslation,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            // Optional: Show more info like article/plural on the back
                            if (currentItem.article != "none" || currentItem.plural.isNotBlank()) {
                                Text(
                                    text = stringResource(
                                        R.string.study_word_grammar,
                                        currentItem.article,
                                        currentItem.germanText,
                                        currentItem.plural.ifBlank { "\u2014" }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        // Ebbinghaus Feedback Row
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

        Spacer(modifier = Modifier.height(24.dp))
        
        // Skip Button for non-scored advancement
        TextButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            viewModel.skipCard()
        }) {
            Text(
                text = stringResource(R.string.study_skip),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceMuted
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
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

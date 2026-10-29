package com.aus.deutschflow.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.service.ReviewQuality
import com.aus.deutschflow.ui.components.*
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.StudyViewModel

/** What the session counter and progress bar show. */
internal data class StudySessionProgress(
    val totalInSession: Int,
    val currentCardNumber: Int,
    val fraction: Float
)

/**
 * The session total is everything answered plus everything still queued.
 *
 * It used to be `max(initialSize, remaining)`, but an Again rating counts as reviewed
 * and puts the card back in the queue, so the real total grows past the starting
 * size. With three cards and one Again the counter read 3 / 3 and the bar sat at 100%
 * with a card still to answer. Mirrors the web study page.
 */
internal fun studySessionProgress(
    initialSize: Int,
    reviewed: Int,
    remaining: Int
): StudySessionProgress {
    val total = maxOf(initialSize, reviewed + remaining)
    val current = (reviewed + 1).coerceAtMost(total)
    val fraction = if (total > 0) (reviewed.toFloat() / total).coerceIn(0f, 1f) else 0f
    return StudySessionProgress(total, current, fraction)
}

@Composable
fun StudyScreen(viewModel: StudyViewModel = hiltViewModel()) {
    val hasLoaded by viewModel.hasLoaded.collectAsStateWithLifecycle()
    val isExtraPractice by viewModel.isExtraPractice.collectAsStateWithLifecycle()
    val studyList by viewModel.studyList.collectAsStateWithLifecycle()
    val dueCount by viewModel.dueCount.collectAsStateWithLifecycle()

    var userSelectedTab by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(1) } // 0 = Dashboard, 1 = Flashcards
    // One automatic tab choice per screen entry, made when the freshly-loaded
    // session state first arrives. Keying the effect on studyList.size as well
    // meant answering the final card emptied the deck, re-fired the effect and
    // yanked the user to the Dashboard before the Session Complete view ever
    // rendered. The flag survives recomposition (and rotation) so the choice is
    // never re-made for this entry; a user who leaves and re-enters the screen
    // gets a fresh decision, which is the intended behaviour.
    var autoTabChosen by rememberSaveable { mutableStateOf(false) }

    // The Dashboard's due banner reads this deck too, so it has to load even when
    // the screen comes back on the Dashboard tab (after rotation or process death)
    // and Flashcards, which used to be the only caller, is never composed.
    LaunchedEffect(Unit) { viewModel.ensureSessionStarted() }

    LaunchedEffect(hasLoaded) {
        if (!userSelectedTab && !autoTabChosen && hasLoaded) {
            autoTabChosen = true
            val hasDueCards = !isExtraPractice && studyList.isNotEmpty()
            selectedTab = if (hasDueCards) 1 else 0
        }
    }

    val tabLabels = listOf(
        stringResource(R.string.dashboard_tab),
        stringResource(R.string.dashboard_flashcards_tab)
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(Spacing.xs))
        SegmentedTabs(
            selectedIndex = selectedTab,
            tabs = tabLabels,
            onTabSelected = {
                userSelectedTab = true
                selectedTab = it
            }
        )
        Spacer(modifier = Modifier.height(Spacing.xs))

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                DashboardScreen(dueCount = dueCount, onStartReview = {
                    userSelectedTab = true
                    selectedTab = 1
                })
            } else {
                StudySessionContent(
                    viewModel = viewModel,
                    onNavigateToDashboard = {
                        userSelectedTab = true
                        selectedTab = 0
                    }
                )
            }
        }
    }
}

@Composable
private fun StudySessionContent(
    viewModel: StudyViewModel,
    onNavigateToDashboard: () -> Unit
) {
    val studyList by viewModel.studyList.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()
    val isFlipped by viewModel.isFlipped.collectAsStateWithLifecycle()
    val hasLoaded by viewModel.hasLoaded.collectAsStateWithLifecycle()
    val allWordsCount by viewModel.allWordsCount.collectAsStateWithLifecycle()
    val sessionReviewedCount by viewModel.sessionReviewedCount.collectAsStateWithLifecycle()
    val initialSessionSize by viewModel.initialSessionSize.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val ttsError by viewModel.ttsError.collectAsStateWithLifecycle()
    // Both of these existed on the ViewModel and were rendered by nothing, so a
    // review that failed to save looked exactly like one that succeeded, and an
    // extra-practice sitting looked exactly like a scheduled one. The web port
    // has shown both since they were added.
    val reviewError by viewModel.reviewError.collectAsStateWithLifecycle()
    val isExtraPractice by viewModel.isExtraPractice.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        viewModel.dismissTtsError()
        // Idempotent: this composable leaves composition on every tab switch, so a
        // plain startSession() here reshuffled the deck and reset the counters
        // mid-session on every return to Flashcards.
        viewModel.ensureSessionStarted()
    }

    // The voice is a @Singleton and outlives this screen; without this a word
    // spoken here keeps playing after the user switches tabs or backgrounds the
    // app. Same discipline the recording screens apply to the microphone.
    OnLeavingScreen { viewModel.stopSpeaking() }

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
        val reviewedTotal = sessionReviewedCount.coerceAtLeast(initialSessionSize)
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
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        border = BorderStroke(2.dp, AppTheme.colors.azureDeep.copy(alpha = 0.5f)),
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
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
                    if (reviewedTotal > 0) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Surface(
                            color = AppTheme.colors.azureDeep.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AppTheme.colors.azureDeep.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = stringResource(R.string.study_reviewed_stat, reviewedTotal),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.azureGlow,
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                            )
                        }
                    }
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

    val (totalInSession, currentCardNumber, sessionProgress) =
        studySessionProgress(initialSessionSize, sessionReviewedCount, studyList.size)
    val animatedProgress by animateFloatAsState(
        targetValue = sessionProgress,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "studySessionProgress"
    )

    // Keyed on the extra-practice flag as well as the card: each badge now says what
    // that rating actually writes, and the answer differs between a scheduled review
    // and extra practice. Remembering per card alone would keep showing a scheduled
    // review's interval after the same card reappears in an extra-practice sitting.
    val againInterval = remember(currentItem, isExtraPractice) { viewModel.getIntervalPreview(currentItem, ReviewQuality.AGAIN) }
    val hardInterval = remember(currentItem, isExtraPractice) { viewModel.getIntervalPreview(currentItem, ReviewQuality.HARD) }
    val goodInterval = remember(currentItem, isExtraPractice) { viewModel.getIntervalPreview(currentItem, ReviewQuality.GOOD) }
    val easyInterval = remember(currentItem, isExtraPractice) { viewModel.getIntervalPreview(currentItem, ReviewQuality.EASY) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The failed write wins the banner: it is about the action the user just
        // took, where a TTS error may be left over from a card ago.
        ErrorBanner(reviewError?.let { stringResource(it) } ?: ttsError)

        // Nothing was due, so this sitting is a bonus. Said out loud, because a
        // schedule that quietly does not move is indistinguishable from a broken one.
        if (isExtraPractice && studyList.isNotEmpty()) {
            Text(
                text = stringResource(R.string.study_extra_practice),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs)
            )
        }

        // Progress & Context Header
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = stringResource(R.string.study_progress_counter, currentCardNumber, totalInSession),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.study_remaining, studyList.size, studyList.size),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xs))

        // Animated Session Progress Bar
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        val rotation by animateFloatAsState(
            targetValue = if (isFlipped) 180f else 0f,
            animationSpec = tween(durationMillis = motionDuration(Motion.DELIBERATE), easing = FastOutSlowInEasing),
            label = "cardFlip"
        )
        val scale by animateFloatAsState(
            targetValue = if (isFlipped) 0.985f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "cardScale"
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
                    .heightIn(min = 280.dp, max = 420.dp)
                    .graphicsLayer {
                        rotationY = rotation
                        cameraDistance = 16f * density
                        scaleX = scale
                        scaleY = scale
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
                // Card Front: German Word & Article
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg)
                        .graphicsLayer {
                            alpha = if (rotation <= 90f) 1f else 0f
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (currentItem.article != "none" && currentItem.article.isNotBlank()) {
                            ArticleBadge(article = currentItem.article)
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
                            modifier = Modifier.size(48.dp)
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
                }

                // Card Back: English Translation & Context
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg)
                        .graphicsLayer {
                            rotationY = 180f
                            alpha = if (rotation > 90f) 1f else 0f
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.library_field_translation).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = currentItem.englishTranslation,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        if (currentItem.plural.isNotBlank()) {
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.word_sheet_plural, currentItem.plural),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp)
                                )
                            }
                        }
                        if (currentItem.exampleSentence.isNotBlank()) {
                            Spacer(modifier = Modifier.height(Spacing.md))
                            Surface(
                                color = AppTheme.colors.glassFillRaised,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth(0.92f)
                            ) {
                                Text(
                                    text = "„${currentItem.exampleSentence}“",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = stringResource(R.string.study_tap_to_flip),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // 4-Tier SRS Spaced Repetition Quality Buttons with Live Interval Previews
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            StudyFeedbackButton(
                label = stringResource(R.string.study_again),
                subtitle = stringResource(againInterval.resId, againInterval.count),
                glow = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting && isFlipped,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.submitReview(ReviewQuality.AGAIN)
                }
            )
            StudyFeedbackButton(
                label = stringResource(R.string.study_hard),
                subtitle = stringResource(hardInterval.resId, hardInterval.count),
                glow = AppTheme.colors.warningAmber,
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting && isFlipped,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.submitReview(ReviewQuality.HARD)
                }
            )
            StudyFeedbackButton(
                label = stringResource(R.string.study_good),
                subtitle = stringResource(goodInterval.resId, goodInterval.count),
                glow = AppTheme.colors.azureDeep,
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting && isFlipped,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.submitReview(ReviewQuality.GOOD)
                }
            )
            StudyFeedbackButton(
                label = stringResource(R.string.study_easy),
                subtitle = stringResource(easyInterval.resId, easyInterval.count),
                glow = AppTheme.colors.tertiaryGreen,
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting && isFlipped,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.submitReview(ReviewQuality.EASY)
                }
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        // Skip Card button. Inert while a review is being written, for the same reason
        // the rating buttons are - see StudyViewModel.skipCard.
        TextButton(
            enabled = !isSubmitting,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.skipCard()
            }
        ) {
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
private fun ArticleBadge(article: String, modifier: Modifier = Modifier) {
    val norm = article.lowercase().trim()
    val (bgColor, textColor, borderColor) = when (norm) {
        "der" -> Triple(
            AppTheme.colors.azureDeep.copy(alpha = 0.15f),
            AppTheme.colors.azureGlow,
            AppTheme.colors.azureDeep.copy(alpha = 0.35f)
        )
        // The theme's error red, which DESIGN_SYSTEM.md assigns to "die". A raw rose
        // (#FB7185) was the same in both themes, so on the light card it was under
        // 2.7:1 - short of the 4.5:1 the badge's small text needs.
        "die" -> Triple(
            MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
        )
        "das" -> Triple(
            AppTheme.colors.tertiaryGreen.copy(alpha = 0.15f),
            AppTheme.colors.tertiaryGreen,
            AppTheme.colors.tertiaryGreen.copy(alpha = 0.35f)
        )
        else -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Color.Transparent
        )
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
        border = if (borderColor != Color.Transparent) BorderStroke(1.dp, borderColor) else null,
        modifier = modifier
    ) {
        Text(
            text = article.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp)
        )
    }
}

@Composable
private fun StudyFeedbackButton(
    label: String,
    glow: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        glow = glow,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Up to two lines, centred: at 200% font scale one line clipped
            // "Nochmal" and "Leicht" mid-word in a quarter-width button. GlassButton
            // grows to fit rather than cutting the second line off.
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}


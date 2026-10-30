package com.aus.deutschflow.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.GlassButton
import com.aus.deutschflow.ui.components.GlassmorphicCard
import com.aus.deutschflow.ui.components.PrimaryActionButton
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.DashboardViewModel
import com.aus.deutschflow.ui.viewmodel.MasteryStats
import com.aus.deutschflow.ui.viewmodel.StudyViewModel
import java.time.LocalDate

/**
 * The Study dashboard, in the order of the questions it answers: what to do today,
 * how today is going, then how the library is doing over time. Every figure is one
 * the ViewModels already hold. Mirrors web/src/components/ui/DashboardContent.tsx.
 */
@Composable
fun DashboardScreen(
    dueCount: Int?,
    onStartReview: () -> Unit = {},
    onStartTranscript: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val userStats by viewModel.userStats.collectAsStateWithLifecycle()
    val activityLog by viewModel.activityLog.collectAsStateWithLifecycle()
    val masteryStats by viewModel.masteryStats.collectAsStateWithLifecycle()
    val todayXp by viewModel.todayXp.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        val stats = masteryStats
        // Both counts, or neither: drawn on half the facts, the card would tell a
        // full library to start one for a frame.
        if (dueCount != null && stats != null) {
            TodayCard(
                dueCount = dueCount,
                totalWords = stats.totalWords,
                onStartReview = onStartReview,
                onStartTranscript = onStartTranscript
            )
        }
        DailyGoalCard(
            xp = todayXp,
            // The live streak, not the stored one: see UserStatsEntity.currentStreak.
            streak = userStats?.currentStreak() ?: 0
        )
        SectionLabel(
            text = stringResource(R.string.dashboard_progress),
            modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs)
        )
        MasteryBreakdownCard(stats ?: MasteryStats(0, 0, 0, 0))
        ActivityHeatmapCard(activityLog)
        Spacer(modifier = Modifier.height(Spacing.xl))
    }
}

/** The dashboard's one section label: small, uppercase, primary. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = UppercaseLabelTracking,
        modifier = modifier.semantics { heading() }
    )
}

/**
 * Three honest states rather than one card with a zero in it: an empty library is
 * sent to Transcript, due cards get Start review, and nothing due offers practice.
 * An empty library used to read "All caught up!" - true, and no help to someone
 * who has not started.
 *
 * The count is the live queue's (see StudyViewModel.remainingDueCount), so it
 * falls as cards are answered rather than holding the number the sitting began
 * with. Practise anyway opens the same Flashcards tab, which serves extra practice
 * when nothing is due and says so.
 */
@Composable
private fun TodayCard(
    dueCount: Int,
    totalWords: Int,
    onStartReview: () -> Unit,
    onStartTranscript: () -> Unit
) {
    val title: String
    val body: String
    when {
        totalWords == 0 -> {
            title = stringResource(R.string.dashboard_no_words)
            body = stringResource(R.string.dashboard_no_words_body)
        }
        dueCount > 0 -> {
            title = pluralStringResource(R.plurals.dashboard_due_banner, dueCount, dueCount)
            body = stringResource(R.string.dashboard_due_body)
        }
        else -> {
            title = stringResource(R.string.dashboard_all_caught_up)
            body = stringResource(R.string.dashboard_all_caught_up_body)
        }
    }

    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.lg)
    ) {
        // One announcement for label, headline and body, so TalkBack reads the
        // count with its explanation instead of three separate stops.
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
            SectionLabel(stringResource(R.string.dashboard_today))
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(Spacing.lg))
        when {
            totalWords == 0 -> PrimaryActionButton(
                text = stringResource(R.string.dashboard_go_to_transcript),
                onClick = onStartTranscript,
                modifier = Modifier.fillMaxWidth()
            )
            dueCount > 0 -> PrimaryActionButton(
                text = stringResource(R.string.dashboard_start_review),
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) },
                onClick = onStartReview,
                modifier = Modifier.fillMaxWidth()
            )
            // The quieter button: nothing is owed, so nothing should shout.
            else -> GlassButton(
                onClick = onStartReview,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ActionButtonHeight)
            ) {
                Text(
                    text = stringResource(R.string.dashboard_practice_anyway),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DailyGoalCard(
    xp: Int,
    streak: Int
) {
    // The goal the ring is drawn against, from the one place that knows what a
    // reviewed card pays - not a bare literal that could drift from it.
    val goal = StudyViewModel.DAILY_XP_GOAL.toFloat()
    val progress = (xp.toFloat() / goal).coerceIn(0f, 1f)
    // A DrawScope is not a composable, so the tokens are read here and closed over.
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.lg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
                val progressPercent = (progress * 100).toInt()
                val goalDescription = stringResource(
                    R.string.dashboard_goal_progress_a11y, xp, goal.toInt(), progressPercent
                )
                Canvas(
                    modifier = Modifier
                        .size(88.dp)
                        .semantics {
                            contentDescription = goalDescription
                        }
                ) {
                    val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = stroke
                    )
                    drawArc(
                        color = primaryColor,
                        startAngle = -90f,
                        sweepAngle = progress * 360f,
                        useCenter = false,
                        style = stroke
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = xp.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.dashboard_xp_of_goal, goal.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.dashboard_daily_goal))
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = if (progress >= 1f) stringResource(R.string.dashboard_goal_achieved)
                    else stringResource(R.string.dashboard_xp_remaining, (goal - xp).toInt()),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Surface(
                    color = AppTheme.colors.warningContainer,
                    shape = CircleShape
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.dashboard_streak, streak, streak),
                        modifier = Modifier.padding(horizontal = Spacing.sm + Spacing.xs, vertical = Spacing.xs),
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.colors.onWarningContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun MasteryBreakdownCard(stats: MasteryStats) {
    val total = stats.totalWords
    // The web dashboard's three swatches: tertiary, azure, outline.
    val segments = listOf(
        Triple(stringResource(R.string.dashboard_mastered), stats.masteredWords, AppTheme.colors.tertiaryGreen),
        Triple(stringResource(R.string.dashboard_learning), stats.learningWords, AppTheme.colors.azureGlow),
        Triple(stringResource(R.string.dashboard_new), stats.newWords, MaterialTheme.colorScheme.outline)
    )

    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.lg)
    ) {
        Text(
            text = stringResource(R.string.dashboard_retention),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = pluralStringResource(R.plurals.dashboard_words_total, total, total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.md))

        // The three buckets as one bar, so the split reads at a glance. Hidden from
        // TalkBack: the counts below carry the same information as text.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clearAndSetSemantics {}
        ) {
            if (total > 0) {
                segments.forEach { (_, count, color) ->
                    if (count > 0) {
                        Box(
                            modifier = Modifier
                                .weight(count.toFloat())
                                .fillMaxHeight()
                                .background(color)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            segments.forEach { (label, count, color) ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {}
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityHeatmapCard(logs: List<com.aus.deutschflow.data.local.entities.ActivityEntity>) {
    val today = LocalDate.now()
    val weeksToShow = 12
    val daysToShow = weeksToShow * 7
    val activityMap = remember(logs) { logs.associate { it.date to it.xpGained } }

    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.lg)
    ) {
        Text(
            text = stringResource(R.string.dashboard_heatmap),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.dashboard_heatmap_sub),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.md))

        val activeDays = remember(activityMap) { activityMap.count { it.value > 0 } }
        val activeColor = AppTheme.colors.tertiaryGreen
        // The empty cell used to be white at 6%, which on a light ground is a white
        // square on a white card. The ink colour at the same alpha is a faint grey
        // in either theme, which is what it was always meant to be.
        val emptyColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        val heatmapDescription = stringResource(
            R.string.dashboard_heatmap_a11y, activeDays, daysToShow
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .semantics {
                    contentDescription = heatmapDescription
                }
        ) {
            val cellSize = 11.dp.toPx()
            val spacing = 3.5.dp.toPx()
            for (i in 0 until daysToShow) {
                val date = today.minusDays((daysToShow - 1 - i).toLong())
                val xp = activityMap[date.toString()] ?: 0
                val col = i / 7
                val row = i % 7
                val color = when {
                    xp >= 100 -> activeColor
                    xp >= 50 -> activeColor.copy(alpha = 0.75f)
                    xp >= 20 -> activeColor.copy(alpha = 0.45f)
                    xp > 0 -> activeColor.copy(alpha = 0.25f)
                    else -> emptyColor
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(col * (cellSize + spacing), row * (cellSize + spacing)),
                    size = Size(cellSize, cellSize),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        // Heatmap Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.dashboard_heatmap_less),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            listOf(
                // The ink colour, not white: a white square at 6% on a light card is
                // a white square on a white card. Matches the empty cell above.
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                AppTheme.colors.tertiaryGreen.copy(alpha = 0.25f),
                AppTheme.colors.tertiaryGreen.copy(alpha = 0.45f),
                AppTheme.colors.tertiaryGreen.copy(alpha = 0.75f),
                AppTheme.colors.tertiaryGreen
            ).forEach { squareColor ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(squareColor)
                )
                Spacer(modifier = Modifier.width(3.dp))
            }
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(
                text = stringResource(R.string.dashboard_heatmap_more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


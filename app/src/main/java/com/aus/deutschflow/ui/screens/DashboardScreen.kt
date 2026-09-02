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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.GlassmorphicCard
import com.aus.deutschflow.ui.components.PrimaryActionButton
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.DashboardViewModel
import com.aus.deutschflow.ui.viewmodel.StudyViewModel
import java.time.LocalDate

@Composable
fun DashboardScreen(
    onStartReview: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val userStats by viewModel.userStats.collectAsState()
    val activityLog by viewModel.activityLog.collectAsState()
    val masteryStats by viewModel.masteryStats.collectAsState()
    val todayXp by viewModel.todayXp.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        DailyGoalCard(
            xp = todayXp,
            streak = userStats?.streak ?: 0,
            onStartReview = onStartReview
        )
        MasteryBreakdownCard(masteryStats)
        ActivityHeatmapCard(activityLog)
        Spacer(modifier = Modifier.height(Spacing.xl))
    }
}

@Composable
fun DailyGoalCard(
    xp: Int,
    streak: Int,
    onStartReview: () -> Unit
) {
    // The goal the ring is drawn against, from the one place that knows what a
    // reviewed card pays - not a bare literal that could drift from it.
    val goal = StudyViewModel.DAILY_XP_GOAL.toFloat()
    val progress = (xp.toFloat() / goal).coerceIn(0f, 1f)
    val primaryColor = MaterialTheme.colorScheme.primary
    // A DrawScope is not a composable, so the token is read here and closed over.
    val glowColor = AppTheme.colors.azureGlow

    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.lg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
                val progressPercent = (progress * 100).toInt()
                val goalDescription = stringResource(
                    R.string.dashboard_goal_progress_a11y, xp, goal.toInt(), progressPercent
                )
                Canvas(
                    modifier = Modifier
                        .size(100.dp)
                        .semantics {
                            contentDescription = goalDescription
                        }
                ) {
                    drawArc(
                        color = primaryColor.copy(alpha = 0.12f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(listOf(glowColor, primaryColor)),
                        startAngle = -90f,
                        sweepAngle = progress * 360f,
                        useCenter = false,
                        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = xp.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.dashboard_xp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dashboard_daily_goal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.dashboard_xp_format, xp, goal.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.azureGlow
                )
                Text(
                    text = if (progress >= 1f) stringResource(R.string.dashboard_goal_achieved)
                    else stringResource(R.string.dashboard_xp_remaining, (goal - xp).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress >= 1f) AppTheme.colors.tertiaryGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.dashboard_streak, streak, streak),
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))
        PrimaryActionButton(
            text = stringResource(R.string.dashboard_start_review),
            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) },
            onClick = onStartReview,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun MasteryBreakdownCard(stats: com.aus.deutschflow.ui.viewmodel.MasteryStats) {
    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.lg)
    ) {
        Text(
            text = stringResource(R.string.dashboard_retention),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            RetentionTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_mastered),
                count = stats.masteredWords,
                color = AppTheme.colors.tertiaryGreen
            )
            RetentionTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_learning),
                count = stats.learningWords,
                color = AppTheme.colors.azureGlow
            )
            RetentionTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_new),
                count = stats.newWords,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RetentionTile(modifier: Modifier, label: String, count: Int, color: Color) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.md)
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ActivityHeatmapCard(logs: List<com.aus.deutschflow.data.local.entities.ActivityEntity>) {
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
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.dashboard_heatmap_sub),
            style = MaterialTheme.typography.bodySmall,
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


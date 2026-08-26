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
    val goal = 50f
    val progress = (xp.toFloat() / goal).coerceIn(0f, 1f)
    val primaryColor = MaterialTheme.colorScheme.primary

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
                Canvas(
                    modifier = Modifier
                        .size(100.dp)
                        .semantics {
                            contentDescription = "$xp of ${goal.toInt()} XP, $progressPercent percent"
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
                        brush = Brush.sweepGradient(listOf(AzureGlow, primaryColor)),
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
                    color = AzureGlow
                )
                Text(
                    text = if (progress >= 1f) stringResource(R.string.dashboard_goal_achieved)
                    else stringResource(R.string.dashboard_xp_remaining, (goal - xp).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress >= 1f) TertiaryGreen else MaterialTheme.colorScheme.onSurfaceVariant
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
            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White) },
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
                color = TertiaryGreen
            )
            RetentionTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_learning),
                count = stats.learningWords,
                color = AzureGlow
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
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .semantics {
                    contentDescription = "Activity heatmap, $activeDays active days in the last $daysToShow days"
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
                    xp >= 100 -> TertiaryGreen
                    xp >= 50 -> TertiaryGreen.copy(alpha = 0.75f)
                    xp >= 20 -> TertiaryGreen.copy(alpha = 0.45f)
                    xp > 0 -> TertiaryGreen.copy(alpha = 0.25f)
                    else -> Color.White.copy(alpha = 0.06f)
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
                Color.White.copy(alpha = 0.06f),
                TertiaryGreen.copy(alpha = 0.25f),
                TertiaryGreen.copy(alpha = 0.45f),
                TertiaryGreen.copy(alpha = 0.75f),
                TertiaryGreen
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


package com.aus.deutschflow.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.theme.AzureGlow
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.TertiaryGreen
import com.aus.deutschflow.ui.viewmodel.DashboardViewModel
import java.time.LocalDate

/*
 * Defaults are hiltViewModel(), not viewModel().
 *
 * Every one of these view models is a @HiltViewModel with an @Inject constructor,
 * and a NavBackStackEntry's default factory is not Hilt-aware - so `viewModel()`
 * reached the plain NewInstanceFactory and threw "Cannot create an instance of ..."
 * for anything with constructor arguments. It only ever worked because each
 * composable() in Navigation.kt passed hiltViewModel() explicitly, which made the
 * defaults dead code everywhere they were correct and a crash everywhere they were
 * used: Practice supplied one of its two, and Study called DashboardScreen() with
 * none. Both tabs crashed on open.
 *
 * With hiltViewModel() as the default, omitting the argument is no longer a way to
 * get it wrong.
 */
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val userStats by viewModel.userStats.collectAsState()
    val activityLog by viewModel.activityLog.collectAsState()
    val masteryStats by viewModel.masteryStats.collectAsState()
    val todayXp by viewModel.todayXp.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        DailyGoalCard(todayXp, userStats?.streak ?: 0)
        MasteryBreakdownCard(masteryStats)
        ActivityHeatmapCard(activityLog)
        Spacer(modifier = Modifier.height(Spacing.xl))
    }
}

@Composable
fun DailyGoalCard(xp: Int, streak: Int) {
    val goal = 50f
    val progress = (xp.toFloat() / goal).coerceIn(0f, 1f)
    val primaryColor = MaterialTheme.colorScheme.primary

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
                Canvas(modifier = Modifier.size(100.dp)) {
                    drawArc(
                        color = primaryColor.copy(alpha = 0.1f),
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
                    Text(text = xp.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text(text = stringResource(R.string.dashboard_xp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.width(Spacing.lg))

            Column {
                Text(text = stringResource(R.string.dashboard_daily_goal), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (progress >= 1f) stringResource(R.string.dashboard_goal_achieved) 
                           else stringResource(R.string.dashboard_xp_remaining, (goal - xp).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress >= 1f) TertiaryGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = CircleShape
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.dashboard_streak, streak, streak),
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun MasteryBreakdownCard(stats: com.aus.deutschflow.ui.viewmodel.MasteryStats) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(text = stringResource(R.string.dashboard_retention), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(Spacing.md))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RetentionTile(Modifier.weight(1f), stringResource(R.string.dashboard_mastered), stats.masteredWords, TertiaryGreen)
                RetentionTile(Modifier.weight(1f), stringResource(R.string.dashboard_learning), stats.learningWords, AzureGlow)
                RetentionTile(Modifier.weight(1f), stringResource(R.string.dashboard_new), stats.newWords, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun RetentionTile(modifier: Modifier, label: String, count: Int, color: Color) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            .padding(Spacing.md)
    ) {
        Text(text = count.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = color)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ActivityHeatmapCard(logs: List<com.aus.deutschflow.data.local.entities.ActivityEntity>) {
    val today = LocalDate.now()
    val weeksToShow = 12
    val daysToShow = weeksToShow * 7
    val activityMap = logs.associate { it.date to it.xpGained }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(text = stringResource(R.string.dashboard_heatmap), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(Spacing.md))

            Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                val cellSize = 12.dp.toPx()
                val spacing = 4.dp.toPx()
                for (i in 0 until daysToShow) {
                    val date = today.minusDays((daysToShow - 1 - i).toLong())
                    val xp = activityMap[date.toString()] ?: 0
                    val col = i / 7
                    val row = i % 7
                    val color = when {
                        xp >= 100 -> TertiaryGreen
                        xp >= 50 -> TertiaryGreen.copy(alpha = 0.7f)
                        xp >= 20 -> TertiaryGreen.copy(alpha = 0.4f)
                        xp > 0 -> TertiaryGreen.copy(alpha = 0.2f)
                        else -> Color.White.copy(alpha = 0.05f)
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
            Text(text = stringResource(R.string.dashboard_heatmap_sub), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

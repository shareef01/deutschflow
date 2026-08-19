package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.dao.ActivityDao
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.ActivityEntity
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * The library split three ways, on one axis so the parts always sum to the whole.
 *
 * Bucketing "new" on reviewCount while bucketing "learning" on interval let a word
 * just answered Again (interval 0, reviewCount 0) count as new all over again, and
 * left a word with interval 0 but reviews behind it in no bucket at all - so the
 * three figures did not add up to the total the card printed beside them.
 */
data class MasteryStats(
    val totalWords: Int,
    /** Reviewed, and not due again for [MASTERED_INTERVAL_DAYS] or more. */
    val masteredWords: Int,
    /** Reviewed at least once, still on a short interval. */
    val learningWords: Int,
    /** Never answered. */
    val newWords: Int
)

/** SM-2's graduation point, shared with the web dashboard. */
const val MASTERED_INTERVAL_DAYS = 21

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val activityDao: ActivityDao,
    private val vocabularyDao: VocabularyDao,
    private val userStatsDao: UserStatsDao
) : ViewModel() {

    val userStats: StateFlow<UserStatsEntity?> = userStatsDao.getUserStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activityLog: StateFlow<List<ActivityEntity>> =
        activityDao.getActivitySince(
            LocalDate.now().minusDays(HEATMAP_DAYS).format(DateTimeFormatter.ISO_LOCAL_DATE)
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val masteryStats: StateFlow<MasteryStats> = vocabularyDao.getAllVocabulary()
        .map { list ->
            val (seen, unseen) = list.partition { it.reviewCount > 0 }
            MasteryStats(
                totalWords = list.size,
                masteredWords = seen.count { it.interval >= MASTERED_INTERVAL_DAYS },
                learningWords = seen.count { it.interval < MASTERED_INTERVAL_DAYS },
                newWords = unseen.size
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MasteryStats(0, 0, 0, 0))

    /** XP gained today, under the same local-calendar key StudyViewModel writes. */
    val todayXp: StateFlow<Int> = activityLog.map { list ->
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        list.find { it.date == today }?.xpGained ?: 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private companion object {
        /** The window the heatmap card advertises as "the past three months". */
        const val HEATMAP_DAYS = 92L
    }
}

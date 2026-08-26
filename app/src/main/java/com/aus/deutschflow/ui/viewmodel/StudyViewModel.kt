package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.ActivityDao
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.ReviewQuality
import com.aus.deutschflow.service.SRSEngine
import com.aus.deutschflow.service.TTSHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class StudyViewModel @Inject constructor(
    private val database: AppDatabase,
    private val vocabularyDao: VocabularyDao,
    private val userStatsDao: UserStatsDao,
    private val activityDao: ActivityDao,
    private val preferenceManager: PreferenceManager,
    private val ttsHelper: TTSHelper,
    private val srsEngine: SRSEngine
) : ViewModel() {

    val ttsError: StateFlow<String?> = ttsHelper.error

    private val _studyList = MutableStateFlow<List<VocabularyEntity>>(emptyList())
    val studyList: StateFlow<List<VocabularyEntity>> = _studyList

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _isFlipped = MutableStateFlow(false)
    val isFlipped: StateFlow<Boolean> = _isFlipped

    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded

    private val _sessionReviewedCount = MutableStateFlow(0)
    val sessionReviewedCount: StateFlow<Int> = _sessionReviewedCount

    val allWordsCount: StateFlow<Int> = vocabularyDao.getAllVocabulary()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun startSession() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val dueList = vocabularyDao.getDueVocabulary(now).firstOrNull().orEmpty()
            val allList = vocabularyDao.getAllVocabulary().firstOrNull().orEmpty()
            val targetList = if (dueList.isNotEmpty()) dueList else allList

            _currentIndex.value = 0
            _isFlipped.value = false
            _studyList.value = targetList.shuffled()
            _sessionReviewedCount.value = 0
            _hasLoaded.value = true
        }
    }

    fun restartSession() {
        viewModelScope.launch {
            val allList = vocabularyDao.getAllVocabulary().firstOrNull().orEmpty()
            _currentIndex.value = 0
            _isFlipped.value = false
            _studyList.value = allList.shuffled()
            _sessionReviewedCount.value = 0
        }
    }


    fun flipCard() {
        _isFlipped.value = !_isFlipped.value
    }

    fun submitReview(quality: ReviewQuality) {
        val currentList = _studyList.value
        val index = _currentIndex.value
        val card = currentList.getOrNull(index) ?: return

        viewModelScope.launch {
            val updatedCard = srsEngine.calculateNextReview(card, quality)
            vocabularyDao.updateVocabulary(updatedCard)

            if (quality.value >= ReviewQuality.GOOD.value) {
                rewardCurrentCard(XP_PER_CARD)
            }
            _sessionReviewedCount.value++

            val newList = currentList.toMutableList().apply {

                if (quality == ReviewQuality.AGAIN) {
                    removeAt(index)
                    add(updatedCard)
                } else {
                    removeAt(index)
                }
            }
            
            _studyList.value = newList
            
            if (newList.isEmpty()) {
                _currentIndex.value = 0
            } else if (index >= newList.size) {
                _currentIndex.value = 0
            }
            
            _isFlipped.value = false
        }
    }

    fun skipCard() {
        val size = _studyList.value.size
        if (size > 0) {
            _currentIndex.value = (_currentIndex.value + 1) % size
            _isFlipped.value = false
        }
    }

    fun autoPlay(text: String) {
        viewModelScope.launch {
            if (preferenceManager.isAutoPlayEnabled.first()) {
                ttsHelper.speak(text)
            }
        }
    }

    fun speak(text: String) {
        ttsHelper.speak(text)
    }

    fun dismissTtsError() {
        ttsHelper.dismissError()
    }

    private fun rewardCurrentCard(points: Int) {
        viewModelScope.launch {
            database.withTransaction {
                val stats = userStatsDao.getUserStatsOnce() ?: UserStatsEntity()
                val now = System.currentTimeMillis()
                val dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

                userStatsDao.insertOrUpdate(
                    stats.copy(
                        xp = stats.xp + points,
                        streak = nextStreak(stats.streak, stats.lastActivityTimestamp, now),
                        lastActivityTimestamp = now
                    )
                )
                
                // Record activity log for the heatmap
                activityDao.addXp(dateStr, points)
            }
        }
    }

    companion object {
        const val XP_PER_CARD = 10

        internal fun nextStreak(currentStreak: Int, lastActivity: Long, now: Long): Int {
            if (lastActivity <= 0L || currentStreak <= 0) return 1
            return when (daysBetween(lastActivity, now)) {
                0L -> currentStreak
                1L -> currentStreak + 1
                else -> 1
            }
        }

        private fun daysBetween(from: Long, to: Long): Long {
            val zone = ZoneId.systemDefault()
            return ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(from).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
            )
        }
    }
}

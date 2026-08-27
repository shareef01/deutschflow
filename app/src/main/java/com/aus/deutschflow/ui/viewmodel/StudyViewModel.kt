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

    /**
     * True while a review is being persisted. The feedback buttons stay rendered
     * but inert for that window: without it, two rapid taps both capture the same
     * card and list — two SRS updates on one card, double XP, and the second
     * splice overwriting the first from a stale snapshot.
     */
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting

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
        // Set before the launch, not inside it: two taps in the same frame both
        // read the old value and both got through — the same re-entry discipline
        // RoleplayViewModel.sendInput applies.
        if (_isSubmitting.value) return
        val currentList = _studyList.value
        val index = _currentIndex.value
        val card = currentList.getOrNull(index) ?: return
        _isSubmitting.value = true

        viewModelScope.launch {
            try {
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
            } finally {
                _isSubmitting.value = false
            }
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

        /**
         * The daily goal ring's target, written once: the Dashboard drew its own
         * literal 50 with nothing tying it to what a reviewed card pays out.
         */
        const val DAILY_XP_GOAL = XP_PER_CARD * 5

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

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
import android.util.Log
import com.aus.deutschflow.R
import kotlinx.coroutines.CancellationException
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
     * True when this sitting is extra practice rather than the scheduler's queue.
     *
     * Nothing was due, so the whole library was offered instead. That is worth
     * keeping - a user who has cleared their queue should still be able to drill -
     * but a bonus sitting must not be indistinguishable from a scheduled one, which
     * it was: answering Good on a card due in 90 days re-multiplied its interval
     * from today, so practising early pushed the material further away.
     */
    private val _isExtraPractice = MutableStateFlow(false)
    val isExtraPractice: StateFlow<Boolean> = _isExtraPractice

    /** A review that could not be written, so the screen can say so rather than lie. */
    private val _reviewError = MutableStateFlow<Int?>(null)
    val reviewError: StateFlow<Int?> = _reviewError

    fun dismissReviewError() {
        _reviewError.value = null
    }

    /**
     * True while a review is being persisted. The feedback buttons stay rendered
     * but inert for that window: without it, two rapid taps both capture the same
     * card and list — two SRS updates on one card, double XP, and the second
     * splice overwriting the first from a stale snapshot.
     */
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting

    val allWordsCount: StateFlow<Int> = vocabularyDao.countVocabulary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun startSession() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val dueList = vocabularyDao.getDueVocabulary(now).firstOrNull().orEmpty()
            val allList = vocabularyDao.getAllVocabulary().firstOrNull().orEmpty()
            val isExtra = dueList.isEmpty()
            val targetList = if (isExtra) allList else dueList

            _currentIndex.value = 0
            _isFlipped.value = false
            _isExtraPractice.value = isExtra
            _studyList.value = targetList.shuffled()
            _sessionReviewedCount.value = 0
            _hasLoaded.value = true
        }
    }

    /** Re-drills the whole library. Always extra practice, by definition. */
    fun restartSession() {
        viewModelScope.launch {
            val allList = vocabularyDao.getAllVocabulary().firstOrNull().orEmpty()
            _currentIndex.value = 0
            _isFlipped.value = false
            _isExtraPractice.value = true
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

        val extraPractice = _isExtraPractice.value

        viewModelScope.launch {
            try {
                val rescheduled = srsEngine.calculateNextReview(card, quality)
                val persisted = scheduleFor(card, rescheduled, quality, extraPractice)

                // One transaction, so the card's schedule and the XP it earned commit
                // together or not at all. They used to be two - the second launched in
                // a coroutine of its own - which left a window where the card had
                // advanced and the XP had not, and put the write on a path where a
                // database error escaped viewModelScope and killed the process.
                database.withTransaction {
                    vocabularyDao.updateVocabulary(persisted)
                    if (quality.value >= ReviewQuality.GOOD.value) {
                        awardXp(XP_PER_CARD)
                    }
                }

                _sessionReviewedCount.value++

                val newList = currentList.toMutableList().apply {

                    if (quality == ReviewQuality.AGAIN) {
                        removeAt(index)
                        add(persisted)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never fatal. The transaction rolled back, so the card is exactly
                // where it was and the user can answer it again.
                Log.w(TAG, "Could not record the review", e)
                _reviewError.value = R.string.study_review_not_saved
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    /**
     * What to actually write for this answer.
     *
     * On a scheduled review, the engine's answer. On extra practice, a success
     * changes nothing - the card was not due, and rewarding the user for drilling by
     * pushing the word further away is the opposite of what they asked for. A
     * failure still counts: finding out early that a card is not known is real
     * information, and the schedule should hear it.
     */
    private fun scheduleFor(
        original: VocabularyEntity,
        rescheduled: VocabularyEntity,
        quality: ReviewQuality,
        extraPractice: Boolean
    ): VocabularyEntity = when {
        !extraPractice -> rescheduled
        quality == ReviewQuality.AGAIN -> rescheduled
        else -> original
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

    /**
     * Adds the points, advances the streak and records the day.
     *
     * Called from inside [submitReview]'s transaction rather than opening one of its
     * own, so it commits with the card it belongs to. It used to launch a second
     * coroutine, which meant the re-entry guard could drop before this landed and an
     * exception here had nowhere to go but the default handler.
     */
    private suspend fun awardXp(points: Int) {
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

        // The heatmap's row for today.
        activityDao.addXp(dateStr, points)
    }

    companion object {
        private const val TAG = "StudyViewModel"

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

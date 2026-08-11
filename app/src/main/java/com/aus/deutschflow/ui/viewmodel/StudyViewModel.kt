package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.TTSHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class StudyViewModel @Inject constructor(
    private val database: AppDatabase,
    private val vocabularyDao: VocabularyDao,
    private val userStatsDao: UserStatsDao,
    private val preferenceManager: PreferenceManager,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    /** Raised when a card could not be spoken, so the screen can say why. */
    val ttsError: StateFlow<String?> = ttsHelper.error

    private val _studyList = MutableStateFlow<List<VocabularyEntity>>(emptyList())
    val studyList: StateFlow<List<VocabularyEntity>> = _studyList

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _isFlipped = MutableStateFlow(false)
    val isFlipped: StateFlow<Boolean> = _isFlipped

    /**
     * False until the first snapshot has been read.
     *
     * Without it the screen cannot tell "no words saved" from "not looked yet", and
     * flashed the empty state for a frame on every entry.
     */
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded

    /**
     * Takes a fresh shuffled snapshot. The list is deliberately a snapshot rather
     * than a live flow - re-shuffling mid-session would move the cards under the
     * user - but the screen restarts the session on entry, so words saved since
     * last time do show up.
     *
     * Not called from init: the screen already calls it on entry, and doing both
     * meant two reads and two shuffles every time the tab was opened.
     */
    fun startSession() {
        // Cleared here rather than inside the coroutine: the session begins when the
        // screen says so, and a tap landing before the database read came back would
        // otherwise still be judged against the previous session's banked cards.
        awardedCardIds.clear()

        viewModelScope.launch {
            val list = vocabularyDao.getAllVocabulary().firstOrNull().orEmpty()
            // Index first: a shorter list published before the index resets would
            // leave the screen reading past the end.
            _currentIndex.value = 0
            _isFlipped.value = false
            _studyList.value = list.shuffled()
            _hasLoaded.value = true
        }
    }

    fun flipCard() {
        _isFlipped.value = !_isFlipped.value
    }

    fun nextCard() {
        val size = _studyList.value.size
        if (size > 0) {
            _currentIndex.value = (_currentIndex.value + 1) % size
            _isFlipped.value = false
        }
    }

    /** Speaks the card, unless the user turned auto-play off in Settings. */
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

    /**
     * Cards already banked this session.
     *
     * nextCard() wraps with a modulo, so "Got it!" on a one-word library used to
     * mint XP for as long as somebody kept tapping. A card counts once per session;
     * starting a new session offers them all again.
     */
    private val awardedCardIds = mutableSetOf<Int>()

    /**
     * Banks the card on screen, once, and advances the streak - atomically.
     *
     * The read and the write have to be one unit. Tapping "Got it!" twice in quick
     * succession used to start two coroutines that both read the same row before
     * either wrote, so one award was silently swallowed and lastActivityTimestamp
     * could be written out of order.
     */
    fun rewardCurrentCard(points: Int = XP_PER_CARD) {
        val cardId = _studyList.value.getOrNull(_currentIndex.value)?.id ?: return
        if (!awardedCardIds.add(cardId)) return

        viewModelScope.launch {
            database.withTransaction {
                val stats = userStatsDao.getUserStatsOnce() ?: UserStatsEntity()
                val now = System.currentTimeMillis()

                userStatsDao.insertOrUpdate(
                    stats.copy(
                        xp = stats.xp + points,
                        streak = nextStreak(stats.streak, stats.lastActivityTimestamp, now),
                        lastActivityTimestamp = now
                    )
                )
            }
        }
    }

    companion object {

        /** What one card is worth. */
        const val XP_PER_CARD = 10

        /**
         * Compares calendar days in the device's zone.
         *
         * The old rule treated any gap over 24h as "the next day", so a five-day
         * absence still added to the streak and it could never break; two sessions
         * 23 hours apart, meanwhile, never counted as consecutive days.
         */
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

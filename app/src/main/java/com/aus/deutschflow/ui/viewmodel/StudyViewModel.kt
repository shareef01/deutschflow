package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.TTSHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StudyViewModel @Inject constructor(
    private val vocabularyDao: VocabularyDao,
    private val userStatsDao: UserStatsDao,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    private val _studyList = MutableStateFlow<List<VocabularyEntity>>(emptyList())
    val studyList: StateFlow<List<VocabularyEntity>> = _studyList

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _isFlipped = MutableStateFlow(false)
    val isFlipped: StateFlow<Boolean> = _isFlipped

    init {
        loadVocabulary()
    }

    fun loadVocabulary() {
        viewModelScope.launch {
            vocabularyDao.getAllVocabulary().firstOrNull()?.let { list ->
                _studyList.value = list.shuffled()
                _currentIndex.value = 0
                _isFlipped.value = false
            }
        }
    }

    fun flipCard() {
        _isFlipped.value = !_isFlipped.value
    }

    fun nextCard() {
        if (_studyList.value.isNotEmpty()) {
            _currentIndex.value = (_currentIndex.value + 1) % _studyList.value.size
            _isFlipped.value = false
        }
    }

    fun speak(text: String) {
        ttsHelper.speak(text)
    }

    fun rewardXP(points: Int) {
        viewModelScope.launch {
            val currentStats = userStatsDao.getUserStats().firstOrNull() ?: UserStatsEntity()
            val newXP = currentStats.xp + points
            
            // Basic streak logic
            val now = System.currentTimeMillis()
            val isNextDay = (now - currentStats.lastActivityTimestamp) > 86400000 // 24h
            val newStreak = if (isNextDay) currentStats.streak + 1 else currentStats.streak
            
            userStatsDao.insertOrUpdate(currentStats.copy(
                xp = newXP,
                streak = if (currentStats.streak == 0) 1 else newStreak,
                lastActivityTimestamp = now
            ))
        }
    }
}

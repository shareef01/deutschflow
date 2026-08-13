package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reads back what has already been transcribed.
 *
 * History used to share [TranscriptViewModel]. That looked like reuse, but
 * `hiltViewModel()` scopes to the navigation back stack entry, so the History
 * destination got an instance of its own anyway - one that built a
 * SpeechRecognizerHelper, subscribed to its results and destroyed it in onCleared,
 * for a screen that never records. Nothing was broken by it; it just meant a list and
 * a search box owned the microphone stack. This reads the one table it displays.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val transcriptDao: TranscriptDao
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val transcripts: StateFlow<List<TranscriptEntity>> = _query
        .combine(transcriptDao.getAllTranscripts()) { query, list ->
            if (query.isBlank()) {
                list
            } else {
                list.filter { it.fullText.contains(query, ignoreCase = true) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(query: String) {
        _query.value = query
    }

    fun deleteTranscript(transcript: TranscriptEntity) {
        viewModelScope.launch {
            transcriptDao.deleteTranscript(transcript)
        }
    }
}

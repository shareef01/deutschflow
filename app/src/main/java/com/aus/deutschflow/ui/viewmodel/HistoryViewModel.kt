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

    /**
     * Whether the history holds anything at all, regardless of the search box.
     *
     * [transcripts] is already filtered, so it cannot answer this: an empty result
     * looks identical whether nothing was ever recorded or the query simply matched
     * nothing, and the screen needs to say different things in those two cases.
     */
    val hasAnyHistory: StateFlow<Boolean> = transcriptDao.getAllTranscripts()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * True until the database has answered once.
     *
     * [transcripts] starts at an empty list, which is indistinguishable from a
     * history that really is empty - so the screen showed "No transcripts found"
     * for a frame before the rows arrived, telling the user something false and
     * then correcting itself.
     */
    val isLoading: StateFlow<Boolean> = transcriptDao.getAllTranscripts()
        .map { false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

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

    /**
     * Puts a deleted transcript back, for the snackbar's Undo. The row is re-inserted
     * with its original timestamp (the entity carries it), so it lands exactly where
     * it was in the list.
     */
    fun restoreTranscript(transcript: TranscriptEntity) {
        viewModelScope.launch {
            transcriptDao.insertTranscript(transcript.copy(id = 0))
        }
    }
}

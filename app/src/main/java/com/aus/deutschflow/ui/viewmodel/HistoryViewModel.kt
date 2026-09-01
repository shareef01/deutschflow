package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.service.TTSHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val transcriptDao: TranscriptDao,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    /**
     * Silences the engine when the screen goes away.
     *
     * TTSHelper is a @Singleton and was only ever torn down in
     * MainActivity.onDestroy behind `isFinishing`, which is false when the app is
     * merely backgrounded - so a word spoken here kept playing after the user
     * switched tabs or left the app. The microphone was already released on both
     * paths; the voice was not.
     */
    fun stopSpeaking() {
        ttsHelper.stop()
    }

    fun speak(text: String) {
        if (text.isNotBlank()) {
            ttsHelper.speak(text)
        }
    }


    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /**
     * The history, held once.
     *
     * This screen used to open three separate Room observers over
     * getAllTranscripts() - one for the list, one for "is it empty", one for "has it
     * loaded" - so a single insert re-read and re-materialised every transcript's
     * text four times over. VocabularyViewModel already carries a comment about
     * having fixed exactly this; the fix was never carried across.
     */
    private val history: StateFlow<List<TranscriptEntity>?> = transcriptDao.getAllTranscripts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Whether the history holds anything at all, regardless of the search box.
     *
     * [transcripts] is already filtered, so it cannot answer this: an empty result
     * looks identical whether nothing was ever recorded or the query simply matched
     * nothing, and the screen needs to say different things in those two cases.
     */
    val hasAnyHistory: StateFlow<Boolean> = history
        .map { it?.isNotEmpty() == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * True until the database has answered once.
     *
     * [transcripts] starts at an empty list, which is indistinguishable from a
     * history that really is empty - so the screen showed "No transcripts found"
     * for a frame before the rows arrived, telling the user something false and
     * then correcting itself. The null in [history] is what "not yet answered"
     * means, so this no longer needs an observer of its own to find out.
     */
    val isLoading: StateFlow<Boolean> = history
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val transcripts: StateFlow<List<TranscriptEntity>> = _query
        .combine(history) { query, list ->
            val rows = list.orEmpty()
            if (query.isBlank()) {
                rows
            } else {
                rows.filter { it.fullText.contains(query, ignoreCase = true) }
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

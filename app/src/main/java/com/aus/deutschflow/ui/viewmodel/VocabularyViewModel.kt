package com.aus.deutschflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.service.VocabularyProcessor
import com.aus.deutschflow.ui.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/** How the library orders its rows. Purely a view concern — no persistence. */
enum class VocabularySort { NEWEST, ALPHABETICAL }

@HiltViewModel
class VocabularyViewModel @Inject constructor(
    private val vocabularyDao: VocabularyDao,
    private val vocabularyProcessor: VocabularyProcessor,
    private val ttsHelper: TTSHelper,
    private val widgetUpdater: WidgetUpdater
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortMode = MutableStateFlow(VocabularySort.NEWEST)
    val sortMode: StateFlow<VocabularySort> = _sortMode

    /** Raised when a word could not be spoken, so the screen can say why. */
    val ttsError: StateFlow<String?> = ttsHelper.error

    /**
     * The whole library, unfiltered — the stats strip counts this, not the
     * search result, so the headline numbers do not change while typing.
     *
     * One upstream StateFlow, held once: two separate collectors over
     * `vocabularyDao.getAllVocabulary()` each opened their own Room observer
     * and doubled every emission the screen received.
     */
    private val library: StateFlow<List<VocabularyEntity>> = vocabularyDao.getAllVocabulary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allVocabulary: StateFlow<List<VocabularyEntity>> = library

    val vocabularyList: StateFlow<List<VocabularyEntity>> =
        combine(
            _searchQuery,
            library,
            _sortMode
        ) { query, list, sort ->
            val filtered = if (query.isBlank()) {
                list
            } else {
                list.filter { 
                    it.germanText.contains(query, ignoreCase = true) || 
                    it.englishTranslation.contains(query, ignoreCase = true) 
                }
            }
            // The DAO already orders by timestamp DESC, so NEWEST is the list as it
            // arrives; alphabetical re-orders by the word the user reads first.
            when (sort) {
                VocabularySort.NEWEST -> filtered
                VocabularySort.ALPHABETICAL -> filtered.sortedWith(byGermanAlphabet)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private companion object {

        /**
         * German dictionary order, not UTF-16 order.
         *
         * `sortedBy { lowercase() }` compares code units, and every umlaut sits above
         * 'z' there - so Äpfel, Öl and Übung were all exiled to the bottom of an
         * alphabetical library, which in a German app is the one list that has to be
         * right. A Collator sorts them where a German speaker looks for them, next to
         * A, O and U. The web already does this via localeCompare(_, "de").
         */
        private val germanCollator: Collator = Collator.getInstance(Locale.GERMAN)

        val byGermanAlphabet: Comparator<VocabularyEntity> =
            compareBy(germanCollator) { it.germanText }
    }

    fun setSortMode(mode: VocabularySort) {
        _sortMode.value = mode
    }

    /**
     * Saves a word the user typed in by hand.
     *
     * Until this existed the library could only be filled through Transcript -> Save,
     * which needs a successful AI call - so a missing or rejected API key left no
     * way to put anything in it at all, and Study, Practice and the widget all read
     * from it. Adding a word is now the one path that never leaves the device.
     */
    fun addVocabulary(german: String, english: String) {
        val germanText = german.trim()
        val translation = english.trim()
        if (germanText.isBlank() || translation.isBlank()) return

        viewModelScope.launch {
            vocabularyDao.save(
                VocabularyEntity(germanText = germanText, englishTranslation = translation)
            )
            widgetUpdater.refresh()
        }
    }

    fun deleteVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            vocabularyDao.deleteVocabulary(vocabulary)
            widgetUpdater.refresh()
        }
    }

    /**
     * Puts a deleted word back, for the snackbar's Undo.
     *
     * Deleting was one tap in an overflow menu with no confirmation and no way back,
     * while a *transcript* - the far less valuable thing - already had an Undo. A
     * word can carry months of scheduling, a hand-edited translation and AI-fetched
     * grammar, so the protections were exactly inverted.
     *
     * Through [VocabularyDao.save] with id 0 rather than a bare insert: the word is
     * unique, and if the user typed the same word again in the seconds before
     * pressing Undo, a plain insert would throw a constraint violation into a
     * coroutine. Save folds the two together instead, which is what restoring onto
     * an occupied name most likely means. The SRS fields ride along on the entity.
     */
    fun restoreVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            vocabularyDao.save(vocabulary.copy(id = 0))
            widgetUpdater.refresh()
        }
    }

    /**
     * Applies an edit from the library dialog.
     *
     * Through [VocabularyDao.save] rather than a bare update: the word is unique now, so
     * renaming an entry onto one the library already holds would otherwise throw a
     * constraint violation into a coroutine and take the app down. The two are folded
     * together instead, which is what the user asking for that name most likely meant.
     */
    fun updateVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            vocabularyDao.save(vocabulary)
            widgetUpdater.refresh()
        }
    }

    /**
     * The fallback example, for words with none of their own.
     *
     * Words saved from a transcript carry the example the model wrote for them. Words
     * typed in by hand never went near the model, so they get a generated sentence -
     * which is what the detail screen used to show for every word, including the
     * ones whose real example had been parsed and discarded.
     */
    fun exampleFor(word: String): String = vocabularyProcessor.generateExample(word)

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
        ttsHelper.speak(text)
    }

    /** Called on entry, so a failure from another screen does not greet the user here. */
    fun dismissTtsError() {
        ttsHelper.dismissError()
    }
}

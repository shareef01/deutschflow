package com.aus.deutschflow.ui.viewmodel

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.AIResult
import com.aus.deutschflow.service.GrammarNote
import com.aus.deutschflow.service.SpeechRecognizerHelper
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.service.VocabularyProcessor
import com.aus.deutschflow.service.WordDetails
import com.aus.deutschflow.service.WordDetailsResult
import com.aus.deutschflow.ui.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranscriptViewModel @Inject constructor(
    private val speechRecognizerHelper: SpeechRecognizerHelper,
    private val vocabularyProcessor: VocabularyProcessor,
    private val vocabularyDao: VocabularyDao,
    private val transcriptDao: TranscriptDao,
    private val preferenceManager: PreferenceManager,
    private val widgetUpdater: WidgetUpdater,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    val partialText: StateFlow<String> = speechRecognizerHelper.partialText
    val finalText: StateFlow<String> = speechRecognizerHelper.finalText
    val isListening: StateFlow<Boolean> = speechRecognizerHelper.isListening
    val errorState: StateFlow<String?> = speechRecognizerHelper.errorState
    val ttsError: StateFlow<String?> = ttsHelper.error

    fun speak(text: String) {
        if (text.isNotBlank()) {
            ttsHelper.speak(text)
        }
    }


    /**
     * Instantaneous input level 0..1, for the live waveform. The screen reads it in
     * a draw phase, never into composition — see SpeechRecognizerHelper.rmsLevel.
     */
    val rmsLevel: StateFlow<Float> = speechRecognizerHelper.rmsLevel

    /**
     * The recognition dialect, so the screen can say which German it is listening
     * for ("German · de-AT") rather than a bare "German".
     */
    /**
     * True until the first transcript exists.
     *
     * Derived from the data the app already keeps rather than a new persisted flag:
     * "has this person ever recorded anything" is exactly what the transcripts table
     * answers, and a preference for it would be a second source of the same truth.
     * Gates the introductory copy, which is worth a screen's worth of space once and
     * is in the way every time after that.
     */
    val isFirstRun: StateFlow<Boolean> = transcriptDao.getAllTranscripts()
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val selectedDialect: StateFlow<String> = preferenceManager.selectedDialect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "de-DE")

    private val _isTranslating = MutableStateFlow(false)

    /** True while the recognizer is finishing up or the translation is in flight. */
    val isBusy: StateFlow<Boolean> = combine(
        speechRecognizerHelper.isProcessing,
        _isTranslating
    ) { processing, translating -> processing || translating }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * What the screen is doing, as one value.
     *
     * The states were real but implicit - spread across isListening, isBusy,
     * finalText and translation, and re-derived with a different combination at each
     * place that needed one. Naming them means a screen renders one thing per state
     * instead of inferring it from four booleans, and a state that is added later
     * cannot be half-handled.
     */
    val phase: StateFlow<RecordingPhase> = combine(
        speechRecognizerHelper.isListening,
        isBusy,
        speechRecognizerHelper.finalText
    ) { listening, busy, final ->
        when {
            listening -> RecordingPhase.LISTENING
            busy -> RecordingPhase.TRANSCRIBING
            final.isNotBlank() -> RecordingPhase.RESULT
            else -> RecordingPhase.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordingPhase.IDLE)

    /**
     * Seconds elapsed in the current recording.
     *
     * Speech recognition ends itself on silence, so without a count there is nothing
     * on screen telling you whether it heard you start - only a waveform, which moves
     * for room noise too.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val listeningSeconds: StateFlow<Int> = speechRecognizerHelper.isListening
        .flatMapLatest { listening ->
            if (!listening) {
                flowOf(0)
            } else {
                flow {
                    var elapsed = 0
                    while (true) {
                        emit(elapsed)
                        delay(1_000)
                        elapsed++
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * Whether the microphone was refused.
     *
     * Distinct from the error text, because it is the one failure on this screen the
     * user can actually undo - and only if the app says where to go.
     */
    private val _permissionDenied = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = _permissionDenied

    private val _translation = MutableStateFlow("")
    val translation: StateFlow<String> = _translation

    private val _suggestedWords = MutableStateFlow<List<String>>(emptyList())
    val suggestedWords: StateFlow<List<String>> = _suggestedWords

    private val _grammarNotes = MutableStateFlow<List<GrammarNote>>(emptyList())
    val grammarNotes: StateFlow<List<GrammarNote>> = _grammarNotes

    /**
     * The model's example for the current utterance, kept so that saving can store it.
     *
     * It was parsed and then dropped on the floor: the library screen showed a
     * randomly picked canned template in its place, for every word.
     */
    private val _example = MutableStateFlow("")

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError

    /** The word the user tapped, resolved to its full linguistic anatomy. */
    private val _wordDetails = MutableStateFlow<WordDetails?>(null)
    val wordDetails: StateFlow<WordDetails?> = _wordDetails

    /**
     * The word whose interrogation is in flight, so the chip that was tapped is the one
     * that shows a spinner.
     *
     * The word rather than a bare "loading" flag, and here rather than on the screen.
     * It was both: a `wordDetailLoading` boolean nothing ever read, and a `loadingWord`
     * remembered in the composition and cleared by an effect watching the *result* - so
     * the spinner's truth and the sheet's truth were two states that had to be kept in
     * step by hand, and were not.
     */
    private val _interrogatingWord = MutableStateFlow<String?>(null)
    val interrogatingWord: StateFlow<String?> = _interrogatingWord

    /** Why a single-word interrogation failed, if it did. */
    private val _wordDetailError = MutableStateFlow<String?>(null)
    val wordDetailError: StateFlow<String?> = _wordDetailError

    init {
        // Completed utterances arrive here, once each. Reading finalText straight
        // after stopListening() used to return the *previous* recording, because the
        // engine had not answered yet.
        speechRecognizerHelper.results
            .onEach { handleUtterance(it) }
            .launchIn(viewModelScope)
    }

    /**
     * Switches the language recognition listens for.
     *
     * The chip on the Transcript screen already named the dialect; it just could not
     * change it, so the one place you are reminded which language you are speaking
     * was the one place you could not act on it. Writes the same preference the
     * Settings radio group does - there is one dialect, not two.
     */
    fun selectDialect(dialect: String) {
        viewModelScope.launch { preferenceManager.saveDialect(dialect) }
    }

    fun startListening() {
        _permissionDenied.value = false
        viewModelScope.launch {
            _translation.value = ""
            _suggestedWords.value = emptyList()
            _example.value = ""
            _aiError.value = null
            speechRecognizerHelper.startListening(preferenceManager.selectedDialect.first())
        }
    }

    fun stopListening() {
        speechRecognizerHelper.stopListening()
    }

    /** Called when the screen leaves composition or the app is backgrounded. */
    fun cancelListening() {
        speechRecognizerHelper.cancel()
    }

    /** The user refused the microphone, so say so rather than doing nothing. */
    fun onPermissionDenied() {
        _permissionDenied.value = true
        speechRecognizerHelper.reportPermissionDenied()
    }

    /**
     * Internal rather than private so a test can drive a completed utterance through
     * it. The alternative is starting real speech recognition, which no test can do.
     */
    @VisibleForTesting
    internal suspend fun handleUtterance(text: String) {
        transcriptDao.insertTranscript(TranscriptEntity(fullText = text))

        _isTranslating.value = true
        try {
            when (val result = vocabularyProcessor.processText(text, preferenceManager.apiKey.first())) {
                is AIResult.Success -> {
                    _translation.value = result.translation
                    _suggestedWords.value = result.keywords
                    _example.value = result.example
                    _grammarNotes.value = result.grammarNotes
                }
                is AIResult.Failure -> {
                    // Never let a failure reach the translation field: the Save button
                    // reads it, and an error string would be filed as a translation.
                    _translation.value = ""
                    _suggestedWords.value = emptyList()
                    _example.value = ""
                    _aiError.value = result.message
                }
            }
        } finally {
            _isTranslating.value = false
        }
    }

    /**
     * @return false when there was nothing to save, so the caller can stay quiet
     * instead of confirming a write that did not happen. The screen's snackbar is the
     * only acknowledgement this action gets; announcing a save that was rejected is
     * the same lie [com.aus.deutschflow.data.local.PreferenceManager.saveApiKey]
     * returns a Boolean to avoid.
     */
    fun saveToVocabulary(german: String, english: String): Boolean {
        if (german.isBlank() || english.isBlank()) return false
        viewModelScope.launch {
            vocabularyDao.save(
                VocabularyEntity(
                    germanText = german,
                    englishTranslation = english,
                    exampleSentence = _example.value
                )
            )
            widgetUpdater.refresh()
        }
        return true
    }

    /**
     * The interrogation in flight, so a second tap supersedes the first rather than
     * racing it.
     */
    private var interrogationJob: Job? = null

    /**
     * Fetches the full linguistic anatomy of one extracted word.
     *
     * The previous word's detail is cleared as soon as a new interrogation starts, so
     * the sheet never shows a stale result while the next one is in flight.
     *
     * Clearing was not enough on its own. The chips stay tappable while a request is
     * out, so two taps left two requests running and whichever answered *last* won the
     * sheet - tapping a second word could open the first word's anatomy, and Save then
     * filed the word the user had not tapped. Cancelling means only one answer can
     * ever arrive, and it belongs to the last word tapped.
     */
    fun interrogateWord(word: String) {
        val trimmed = word.trim()
        if (trimmed.isBlank()) return

        interrogationJob?.cancel()
        interrogationJob = viewModelScope.launch {
            _wordDetails.value = null
            _wordDetailError.value = null
            _interrogatingWord.value = trimmed
            try {
                when (val result =
                    vocabularyProcessor.interrogateWord(trimmed, preferenceManager.apiKey.first())) {
                    is WordDetailsResult.Success -> _wordDetails.value = result.details
                    is WordDetailsResult.Failure -> _wordDetailError.value = result.message
                }
            } finally {
                // Only the newest interrogation owns this. A cancelled predecessor runs
                // its finally on resumption, which can land after its successor has
                // already named its own word - clearing it there would drop the spinner
                // off a chip whose answer is still on its way.
                if (coroutineContext[Job] === interrogationJob) {
                    _interrogatingWord.value = null
                }
            }
        }
    }

    /** Closes the detail sheet and forgets the current word. */
    fun dismissWordDetails() {
        _wordDetails.value = null
        _wordDetailError.value = null
    }

    /**
     * Clears a failed interrogation, but only if it is still the failure on screen.
     *
     * Distinct from [dismissWordDetails], which also closes the sheet. The screen
     * reports a failure through a snackbar, and showing one suspends for seconds; a tap
     * on another chip inside that window starts a new interrogation, and the blanket
     * dismiss that used to run afterwards wiped the new word's answer as it arrived.
     * Same rule as SpeechRecognizerHelper's timed error reset: a message that something
     * newer has already replaced is not this caller's to clear.
     */
    fun dismissWordDetailError(message: String) {
        _wordDetailError.compareAndSet(message, null)
    }

    /**
     * Saves exactly one structured word - article, plural, conjugation, meaning and
     * example - rather than the whole transcript.
     *
     * Through [VocabularyDao.save], so interrogating a word the library already holds
     * fills in the grammar it was missing instead of standing a second copy beside it.
     */
    fun saveWordDetails(details: WordDetails): Boolean {
        if (details.word.isBlank() || details.meaning.isBlank()) return false
        viewModelScope.launch {
            vocabularyDao.save(
                VocabularyEntity(
                    germanText = details.word,
                    englishTranslation = details.meaning,
                    exampleSentence = details.exampleSentence,
                    article = details.article,
                    plural = details.plural,
                    conjugation = details.conjugationOrInfinitive,
                    // Comma-joined, the shape LinguisticBox on the detail screen
                    // renders. Without these two the whole chain — prompt, parser,
                    // MIGRATION_8_9, the two boxes — ended in a value nothing wrote.
                    synonyms = details.synonyms.joinToString(", "),
                    antonyms = details.antonyms.joinToString(", ")
                )
            )
            widgetUpdater.refresh()
        }
        return true
    }

    override fun onCleared() {
        speechRecognizerHelper.destroy()
    }
}

/** The states the transcript screen moves through, in the order they occur. */
enum class RecordingPhase { IDLE, LISTENING, TRANSCRIBING, RESULT }

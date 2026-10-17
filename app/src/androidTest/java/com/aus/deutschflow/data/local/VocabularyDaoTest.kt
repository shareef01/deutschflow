package com.aus.deutschflow.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.data.local.dao.MasteryCounts
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.service.DailyWord
import com.aus.deutschflow.data.local.entities.ARCHIVED_VOCABULARY_KEY
import com.aus.deutschflow.data.local.entities.ReviewEventEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VocabularyDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: VocabularyDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.vocabularyDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndGetAllVocabulary() = runBlocking {
        val item = VocabularyEntity(germanText = "Lernen", englishTranslation = "Learn")
        dao.insertVocabulary(item)
        
        val allItems = dao.getAllVocabulary().first()
        assertEquals(1, allItems.size)
        assertEquals("Lernen", allItems[0].germanText)
    }

    @Test
    fun deleteVocabulary() = runBlocking {
        val item = VocabularyEntity(id = 1, germanText = "Lernen", englishTranslation = "Learn")
        dao.insertVocabulary(item)
        dao.deleteVocabulary(item)

        val allItems = dao.getAllVocabulary().first()
        assertTrue(allItems.isEmpty())
    }

    // --- save: the one way a word enters or changes -----------------------------

    @Test
    fun savingAWordTheLibraryDoesNotHaveInsertsIt() = runBlocking {
        dao.save(VocabularyEntity(germanText = "Hund", englishTranslation = "dog"))

        val saved = dao.getAllVocabulary().first()
        assertEquals(1, saved.size)
        assertEquals("Hund", saved.first().germanText)
    }

    @Test
    fun savingTheSameWordAgainEnrichesItRatherThanCopyingIt() = runBlocking {
        dao.save(VocabularyEntity(germanText = "Hund", englishTranslation = "dog"))
        // The same word, interrogated: same meaning, but now with its grammar.
        dao.save(
            VocabularyEntity(
                germanText = "Hund",
                englishTranslation = "dog",
                exampleSentence = "Der Hund schläft.",
                article = "der",
                plural = "Hunde"
            )
        )

        val saved = dao.getAllVocabulary().first()
        assertEquals("one word, not two", 1, saved.size)
        assertEquals("der", saved.first().article)
        assertEquals("Hunde", saved.first().plural)
        assertEquals("Der Hund schläft.", saved.first().exampleSentence)
    }

    @Test
    fun caseAloneDoesNotMakeADifferentWord() = runBlocking {
        dao.save(VocabularyEntity(germanText = "Hund", englishTranslation = "dog"))
        dao.save(VocabularyEntity(germanText = "hund", englishTranslation = "dog", article = "der"))

        val saved = dao.getAllVocabulary().first()
        assertEquals(1, saved.size)
        assertEquals("Hund", saved.first().germanText)
        assertEquals("der", saved.first().article)
    }

    @Test
    fun editingAWordInPlaceIsNotTreatedAsASecondSighting() = runBlocking {
        dao.save(
            VocabularyEntity(
                germanText = "Hund",
                englishTranslation = "dog",
                article = "der"
            )
        )
        val stored = dao.getAllVocabulary().first().first()

        // The user corrects the translation. An explicit edit wins outright - it is a
        // correction, not another sighting - so a blank field here would clear.
        dao.save(stored.copy(englishTranslation = "hound"))

        val saved = dao.getAllVocabulary().first()
        assertEquals(1, saved.size)
        assertEquals("hound", saved.first().englishTranslation)
        assertEquals("der", saved.first().article)
        assertEquals(stored.id, saved.first().id)
    }

    /**
     * The path that would otherwise be a crash: `germanText` is unique, so renaming an
     * entry onto a word the library already holds is a constraint violation thrown into
     * a coroutine with nothing to catch it.
     */
    @Test
    fun renamingAWordOntoAnExistingOneFoldsThemTogether() = runBlocking {
        dao.save(
            VocabularyEntity(
                germanText = "Hund",
                englishTranslation = "dog",
                article = "der"
            )
        )
        dao.save(
            VocabularyEntity(
                germanText = "Hnud",
                englishTranslation = "dog, mistyped",
                exampleSentence = "Der Hund schläft."
            )
        )
        val typo = dao.getAllVocabulary().first().first { it.germanText == "Hnud" }

        // The user fixes the spelling, which collides with the entry already there.
        dao.save(typo.copy(germanText = "Hund"))

        val saved = dao.getAllVocabulary().first()
        assertEquals("the two should be one word", 1, saved.size)
        assertEquals("Hund", saved.first().germanText)
        // Both sides' contributions survive the fold.
        assertEquals("der", saved.first().article)
        assertEquals("Der Hund schläft.", saved.first().exampleSentence)
        assertEquals("dog, mistyped", saved.first().englishTranslation)
    }

    // --- queries that replaced whole-library reads ------------------------------

    /**
     * The word of the day is now chosen in SQL; it must be exactly the word the
     * pure rule (DailyWord.select) picks from the full list, day after day - including
     * after a deletion leaves a gap in the ids.
     */
    @Test
    fun theOneRowWordOfTheDayMatchesTheRule() = runBlocking {
        assertEquals("an empty library has none", null, dao.wordOfTheDay(20_000L))
        repeat(7) { dao.save(VocabularyEntity(germanText = "wort$it", englishTranslation = "word$it")) }
        dao.deleteById(dao.getAllVocabulary().first().minOf { it.id } + 2)

        val all = dao.getAllVocabulary().first()
        for (day in 20_000L until 20_014L) {
            assertEquals("day $day", DailyWord.select(all, day), dao.wordOfTheDay(day))
        }
    }

    /** The Dashboard's split, counted in SQL, matches the buckets it used to compute. */
    @Test
    fun masteryCountsSplitTheLibraryOnOneAxis() = runBlocking {
        assertEquals(MasteryCounts(0, 0, 0, 0), dao.masteryCounts(21).first())

        dao.save(VocabularyEntity(germanText = "neu", englishTranslation = "new"))
        dao.save(VocabularyEntity(germanText = "lernend", englishTranslation = "learning", reviewCount = 2, interval = 6))
        dao.save(VocabularyEntity(germanText = "sicher", englishTranslation = "mastered", reviewCount = 5, interval = 40))
        dao.save(VocabularyEntity(germanText = "grenze", englishTranslation = "boundary", reviewCount = 3, interval = 21))

        assertEquals(MasteryCounts(totalWords = 4, masteredWords = 2, learningWords = 1, newWords = 1), dao.masteryCounts(21).first())
    }

    // --- learning data across merge and undo -------------------------------------

    private suspend fun stored(german: String): VocabularyEntity =
        requireNotNull(dao.findByGermanText(german)) { "$german was never stored" }

    private suspend fun recordReview(vocabularyId: Int) =
        database.reviewEventDao().insert(
            ReviewEventEntity(
                vocabularyId = vocabularyId,
                rating = "GOOD",
                scheduledDays = 1,
                actualDays = 1,
                reviewedAtTimestamp = System.currentTimeMillis(),
                isExtraPractice = false
            )
        )

    private suspend fun eventIds(): List<Int> =
        database.reviewEventDao().getAllEvents().first().map { it.vocabularyId }

    /**
     * Renaming a well-studied card onto a fresh twin used to keep the twin's empty
     * schedule and leave the studied card's history pointing at a deleted id.
     */
    @Test
    fun renamingAStudiedCardOntoANewOneKeepsItsProgressAndHistory() = runBlocking {
        dao.save(VocabularyEntity(germanText = "Hund", englishTranslation = "dog"))
        dao.save(
            VocabularyEntity(
                germanText = "Hnud",
                englishTranslation = "dog",
                nextReview = 123_456L,
                interval = 40,
                easeFactor = 2.7f,
                reviewCount = 6
            )
        )
        val twin = stored("Hund")
        val studied = stored("Hnud")
        recordReview(studied.id)
        recordReview(studied.id)

        dao.save(studied.copy(germanText = "Hund"))

        val saved = dao.getAllVocabulary().first()
        assertEquals("the two should be one word", 1, saved.size)
        val survivor = saved.first()
        assertEquals("the survivor is the row that owned the name", twin.id, survivor.id)
        assertEquals("the further-along schedule wins", 6, survivor.reviewCount)
        assertEquals(40, survivor.interval)
        assertEquals(2.7f, survivor.easeFactor, 0.0001f)
        assertEquals(123_456L, survivor.nextReview)
        assertEquals(
            "the renamed card's history follows it onto the survivor",
            listOf(twin.id, twin.id),
            eventIds()
        )
    }

    /** The other way round: renaming a new card onto a studied one must not reset it. */
    @Test
    fun renamingANewCardOntoAStudiedOneKeepsTheStudiedSchedule() = runBlocking {
        dao.save(
            VocabularyEntity(
                germanText = "Hund",
                englishTranslation = "dog",
                interval = 40,
                reviewCount = 6
            )
        )
        dao.save(VocabularyEntity(germanText = "Hnud", englishTranslation = "dog"))

        dao.save(stored("Hnud").copy(germanText = "Hund"))

        val survivor = dao.getAllVocabulary().first().single()
        assertEquals(6, survivor.reviewCount)
        assertEquals(40, survivor.interval)
    }

    /**
     * Undo used to re-save the word under a fresh id, which cut it off from every
     * review it had ever had. The id is free - the table is AUTOINCREMENT - so it
     * comes back under its own.
     */
    @Test
    fun undoingADeleteBringsTheWordBackWithItsHistory() = runBlocking {
        dao.save(
            VocabularyEntity(
                germanText = "Übung",
                englishTranslation = "exercise",
                interval = 12,
                reviewCount = 3
            )
        )
        val word = stored("Übung")
        recordReview(word.id)

        dao.deleteVocabulary(word)
        dao.restore(word)

        val back = stored("Übung")
        assertEquals("same id, so the history is attached again", word.id, back.id)
        assertEquals(12, back.interval)
        assertEquals(3, back.reviewCount)
        assertEquals(listOf(word.id), eventIds())
    }

    /** Undo after the word was typed in again: one word, the progress, and the history. */
    @Test
    fun undoingOntoAWordSavedAgainFoldsThemAndMovesTheHistory() = runBlocking {
        dao.save(
            VocabularyEntity(
                germanText = "Übung",
                englishTranslation = "exercise",
                interval = 12,
                reviewCount = 3
            )
        )
        val word = stored("Übung")
        recordReview(word.id)
        dao.deleteVocabulary(word)

        // Typed in again before Undo was pressed.
        dao.save(VocabularyEntity(germanText = "übung", englishTranslation = "practice"))
        val retyped = stored("Übung")

        dao.restore(word)

        val saved = dao.getAllVocabulary().first()
        assertEquals("one word, not two", 1, saved.size)
        assertEquals(retyped.id, saved.first().id)
        assertEquals("the restored progress is not lost to the fresh entry", 3, saved.first().reviewCount)
        assertEquals(12, saved.first().interval)
        assertEquals(listOf(retyped.id), eventIds())
    }

    @Test
    fun archivedDeletedVocabularyPlaceholderIsStrictlyIsolatedFromUserDecksAndStats() = runBlocking {
        // 1. Insert internal archived placeholder row directly
        val archived = VocabularyEntity(
            id = 100,
            germanText = "[Gelöscht]",
            germanTextKey = ARCHIVED_VOCABULARY_KEY,
            englishTranslation = "[Archived / Deleted Word]",
            timestamp = 0L,
            nextReview = Long.MAX_VALUE,
            interval = 0,
            reviewCount = 0,
            remoteId = "00000000-0000-4000-8000-000000000000",
            lastModifiedAt = 0L
        )
        dao.insertVocabulary(archived)

        // 2. Verified excluded from user-facing flows
        val allFlow = dao.getAllVocabulary().first()
        assertTrue("Archived placeholder must never appear in getAllVocabulary flow", allFlow.isEmpty())

        val allList = dao.getAllVocabularyList()
        assertTrue("Archived placeholder must never appear in getAllVocabularyList", allList.isEmpty())

        val due = dao.getDueVocabulary(Long.MAX_VALUE).first()
        assertTrue("Archived placeholder must never appear in getDueVocabulary queue", due.isEmpty())

        val countFlow = dao.countVocabulary().first()
        assertEquals("countVocabulary must be 0", 0, countFlow)

        val countOnce = dao.countOnce()
        assertEquals("countOnce must be 0", 0, countOnce)

        val wotd = dao.wordOfTheDay(12345L)
        assertEquals("wordOfTheDay must be null when only archived placeholder exists", null, wotd)

        val mastery = dao.masteryCounts(21).first()
        assertEquals("masteryCounts totalWords must be 0", 0, mastery.totalWords)

        // 3. User attempts to save or claim ARCHIVED_VOCABULARY_KEY are rejected
        dao.save(VocabularyEntity(germanText = "[Gelöscht]", englishTranslation = "should not save"))
        dao.save(VocabularyEntity(germanText = ARCHIVED_VOCABULARY_KEY, englishTranslation = "should not save"))
        assertEquals("User save cannot create normal card with archived key", 0, dao.countOnce())

        // 4. User attempts to restore ARCHIVED_VOCABULARY_KEY are rejected
        dao.restore(archived.copy(id = 0, englishTranslation = "restored evil"))
        assertEquals("User restore cannot recreate archived key as user card", 0, dao.countOnce())

        // 5. Normal word insertion behaves normally alongside archived placeholder
        dao.save(VocabularyEntity(germanText = "Baum", englishTranslation = "tree"))
        assertEquals(1, dao.countOnce())
        val allAfterNormal = dao.getAllVocabularyList()
        assertEquals(1, allAfterNormal.size)
        assertEquals("Baum", allAfterNormal[0].germanText)
    }
}

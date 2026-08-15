package com.aus.deutschflow.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.data.local.dao.VocabularyDao
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
}

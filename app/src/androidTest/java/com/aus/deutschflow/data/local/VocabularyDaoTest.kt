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
}

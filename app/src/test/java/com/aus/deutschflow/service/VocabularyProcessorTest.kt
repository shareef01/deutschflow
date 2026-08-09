package com.aus.deutschflow.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyProcessorTest {

    private val processor = VocabularyProcessor()

    @Test
    fun `extractKeywords should filter short words`() {
        val text = "Hallo ich bin ein Entwickler"
        val keywords = processor.extractKeywords(text)
        
        // "Entwickler" is > 5 chars, others are not (except Hallo which is exactly 5, wait 5 is not > 5)
        // Let's check logic in VocabularyProcessor.kt: it.length > 5
        assertTrue(keywords.contains("Entwickler"))
        assertTrue(!keywords.contains("bin"))
    }

    @Test
    fun `generateExample should return a sentence containing the word`() {
        val word = "Schule"
        val example = processor.generateExample(word)
        assertTrue(example.contains(word))
    }
}

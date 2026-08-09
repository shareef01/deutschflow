package com.aus.deutschflow.service

import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyProcessorTest {

    private val processor = VocabularyProcessor()

    @Test
    fun `generateExample returns a sentence containing the word`() {
        val word = "Schule"

        repeat(20) {
            assertTrue(processor.generateExample(word).contains(word))
        }
    }

    @Test
    fun `generateExample uses the hand written sentence for known words`() {
        assertTrue(processor.generateExample("hallo").contains("wie geht es dir"))
        assertTrue(processor.generateExample("Deutsch").contains("jeden Tag"))
    }
}

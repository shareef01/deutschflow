package com.aus.deutschflow.service

import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyProcessorTest {

    @Test
    fun `generateExample returns a sentence containing the word`() {
        val word = "Schule"

        repeat(20) {
            assertTrue(VocabularyProcessor.generateExample(word).contains(word))
        }
    }

    @Test
    fun `generateExample uses the hand written sentence for known words`() {
        assertTrue(VocabularyProcessor.generateExample("hallo").contains("wie geht es dir"))
        assertTrue(VocabularyProcessor.generateExample("Deutsch").contains("jeden Tag"))
    }
}

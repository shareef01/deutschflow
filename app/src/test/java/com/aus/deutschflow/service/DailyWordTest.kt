package com.aus.deutschflow.service

import com.aus.deutschflow.data.local.entities.VocabularyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DailyWordTest {

    private fun library(size: Int) = (1..size).map {
        VocabularyEntity(id = it, germanText = "wort$it", englishTranslation = "word$it")
    }

    private fun day(year: Int, month: Int, dayOfMonth: Int) =
        LocalDate.of(year, month, dayOfMonth).toEpochDay()

    @Test
    fun `an empty library has no word of the day`() {
        assertNull(DailyWord.select(emptyList(), day(2026, 8, 10)))
    }

    @Test
    fun `the same day always picks the same word`() {
        val words = library(7)
        val today = day(2026, 8, 10)

        val picked = DailyWord.select(words, today)

        // This is the property the widget needs: it is redrawn on every library
        // write, and each redraw must land on the word already on screen.
        repeat(20) {
            assertEquals(picked, DailyWord.select(words, today))
        }
    }

    @Test
    fun `the word changes from one day to the next`() {
        val words = library(7)

        assertEquals(
            false,
            DailyWord.select(words, day(2026, 8, 10)) ==
                DailyWord.select(words, day(2026, 8, 11))
        )
    }

    @Test
    fun `the order the library arrives in does not change the choice`() {
        val words = library(5)
        val today = day(2026, 8, 10)

        // getAllVocabulary sorts by timestamp; ties between words saved in the same
        // millisecond have no defined order, so the choice must not depend on it.
        assertEquals(
            DailyWord.select(words, today),
            DailyWord.select(words.reversed(), today)
        )
    }

    @Test
    fun `a single word library always picks that word`() {
        val only = library(1)

        for (offset in 0..30) {
            assertEquals(only.first(), DailyWord.select(only, day(2026, 8, 10) + offset))
        }
    }

    @Test
    fun `every word gets a turn over a full cycle`() {
        val words = library(5)
        val start = day(2026, 8, 10)

        val seen = (0..4).map { DailyWord.select(words, start + it) }.toSet()

        assertEquals(words.toSet(), seen)
    }

    @Test
    fun `dates before the epoch still land inside the library`() {
        val words = library(5)

        // floorMod, not %: a negative epoch day would index out of bounds.
        val picked = DailyWord.select(words, day(1969, 6, 1))

        assertEquals(true, picked in words)
    }
}

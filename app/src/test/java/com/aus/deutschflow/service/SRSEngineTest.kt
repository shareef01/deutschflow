package com.aus.deutschflow.service

import com.aus.deutschflow.data.local.entities.VocabularyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The scheduler, which is the one piece of this app whose mistakes compound.
 *
 * An interval that is wrong by a day is invisible today and a month off by summer,
 * and nothing else in the suite would notice. The cases below are the SM-2 rules the
 * engine claims to implement, stated as arithmetic rather than as prose.
 *
 * The same table is asserted in web/tests/srs.test.ts. The two implementations are
 * documented as mirrors, and they had already drifted - Kotlin truncated the product
 * where TypeScript rounded it - so the fixture is deliberately shared.
 */
class SRSEngineTest {

    private val engine = SRSEngine()

    private fun card(
        interval: Int = 0,
        easeFactor: Float = 2.5f,
        reviewCount: Int = 0
    ) = VocabularyEntity(
        germanText = "das Haus",
        englishTranslation = "the house",
        interval = interval,
        easeFactor = easeFactor,
        reviewCount = reviewCount
    )

    @Test
    fun theFirstSuccessSchedulesTomorrow() {
        val result = engine.calculateNextReview(card(), ReviewQuality.GOOD)

        assertEquals(1, result.interval)
        assertEquals(1, result.reviewCount)
        // Good is SM-2's q=4, where the ease adjustment works out to exactly zero.
        assertEquals(2.5f, result.easeFactor, 0.0001f)
    }

    @Test
    fun theSecondSuccessSchedulesSixDays() {
        val result = engine.calculateNextReview(card(interval = 1, reviewCount = 1), ReviewQuality.GOOD)

        assertEquals(6, result.interval)
        assertEquals(2, result.reviewCount)
    }

    @Test
    fun laterSuccessesMultiplyByTheEase() {
        val result = engine.calculateNextReview(
            card(interval = 6, easeFactor = 2.5f, reviewCount = 2),
            ReviewQuality.GOOD
        )

        // 6 * 2.5 * 1.0
        assertEquals(15, result.interval)
    }

    /**
     * The case the two implementations disagreed on. 6 * 2.6 is 15.6: truncation gives
     * 15, rounding gives 16, and a synced library would have scheduled the same card
     * differently on the phone and in the browser. Rounding is the rule, both sides.
     */
    @Test
    fun afractionalIntervalRoundsRatherThanTruncating() {
        val result = engine.calculateNextReview(
            card(interval = 6, easeFactor = 2.6f, reviewCount = 3),
            ReviewQuality.GOOD
        )

        assertEquals(16, result.interval)
    }

    @Test
    fun easyStretchesTheIntervalAndRaisesTheEase() {
        val good = engine.calculateNextReview(card(interval = 10, reviewCount = 3), ReviewQuality.GOOD)
        val easy = engine.calculateNextReview(card(interval = 10, reviewCount = 3), ReviewQuality.EASY)

        assertTrue("Easy should schedule further out than Good", easy.interval > good.interval)
        assertEquals(2.6f, easy.easeFactor, 0.0001f)
    }

    @Test
    fun hardShowsTheCardTomorrowAndCostsEase() {
        val result = engine.calculateNextReview(
            card(interval = 30, easeFactor = 2.5f, reviewCount = 5),
            ReviewQuality.HARD
        )

        assertEquals("a card struggled with comes back tomorrow", 1, result.interval)
        assertEquals(2.3f, result.easeFactor, 0.0001f)
        // The history is not thrown away: only Again resets the count.
        assertEquals(5, result.reviewCount)
    }

    @Test
    fun againResetsTheCardAndLeavesItDueNow() {
        val result = engine.calculateNextReview(
            card(interval = 30, easeFactor = 2.5f, reviewCount = 5),
            ReviewQuality.AGAIN
        )

        assertEquals(0, result.interval)
        assertEquals(0, result.reviewCount)
        assertEquals("0 is what the due-query reads as 'ready now'", 0L, result.nextReview)
        assertEquals(2.3f, result.easeFactor, 0.0001f)
    }

    /** SM-2's floor. Without it a repeatedly failed card would eventually schedule backwards. */
    @Test
    fun theEaseNeverFallsBelowTheFloor() {
        var current = card(interval = 5, easeFactor = 1.4f, reviewCount = 3)
        repeat(5) { current = engine.calculateNextReview(current, ReviewQuality.AGAIN) }

        assertEquals(1.3f, current.easeFactor, 0.0001f)
    }

    @Test
    fun aScheduledCardIsDueTheRightNumberOfDaysOut() {
        val before = System.currentTimeMillis()
        val result = engine.calculateNextReview(card(interval = 1, reviewCount = 1), ReviewQuality.GOOD)

        val daysOut = TimeUnit.MILLISECONDS.toDays(result.nextReview - before)
        assertEquals(6L, daysOut)
    }

    /** Nothing about a review may touch the word itself. */
    @Test
    fun theWordAndItsGrammarAreUntouched() {
        val original = card(interval = 3, reviewCount = 2).copy(
            article = "das",
            plural = "Häuser",
            synonyms = "Gebäude"
        )

        val result = engine.calculateNextReview(original, ReviewQuality.GOOD)

        assertEquals(original.germanText, result.germanText)
        assertEquals(original.englishTranslation, result.englishTranslation)
        assertEquals("das", result.article)
        assertEquals("Häuser", result.plural)
        assertEquals("Gebäude", result.synonyms)
        assertEquals(original.id, result.id)
    }
}

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

    /**
     * Hard is a success with a shorter step, not a lapse.
     *
     * It used to return a flat 1, so a mature card lost its entire schedule for an
     * answer that was still correct - which punished the honest button harder than
     * the schedule punished a wrong one.
     */
    @Test
    fun hardShortensTheIntervalRatherThanResettingIt() {
        val result = engine.calculateNextReview(
            card(interval = 30, easeFactor = 2.5f, reviewCount = 5),
            ReviewQuality.HARD
        )

        assertEquals("30 * 1.2", 36, result.interval)
        assertEquals(2.35f, result.easeFactor, 0.0001f)
        // Hard is a success, so the streak advances.
        assertEquals(6, result.reviewCount)
    }

    /** The regression: five Good ratings reach 95 days, and one Hard used to erase them. */
    @Test
    fun aLongStreakSurvivesOneHard() {
        val result = engine.calculateNextReview(
            card(interval = 95, easeFactor = 2.5f, reviewCount = 5),
            ReviewQuality.HARD
        )

        assertTrue("expected 100..120, was ${result.interval}", result.interval in 100..120)
    }

    /**
     * Hard and Again shared a flat -0.2, so the two buttons told the scheduler
     * nothing different about the card.
     */
    @Test
    fun hardAndAgainChargeDifferentEase() {
        val base = card(interval = 10, easeFactor = 2.5f, reviewCount = 3)
        val hard = engine.calculateNextReview(base, ReviewQuality.HARD)
        val again = engine.calculateNextReview(base, ReviewQuality.AGAIN)

        assertTrue(hard.easeFactor > again.easeFactor)
        assertEquals(2.35f, hard.easeFactor, 0.0001f)
        assertEquals(2.3f, again.easeFactor, 0.0001f)
    }

    /** A new card has interval 0, and 0 * 1.2 is still 0 - which reads as a lapse. */
    @Test
    fun hardNeverSchedulesBackwardsOrToZero() {
        val result = engine.calculateNextReview(card(), ReviewQuality.HARD)

        assertEquals(1, result.interval)
        assertTrue(result.nextReview > 0)
    }

    /**
     * Nine Easy ratings used to reach 220 years, which is deletion by another name.
     */
    @Test
    fun theIntervalNeverExceedsTheCeiling() {
        var current = card()
        repeat(10) { current = engine.calculateNextReview(current, ReviewQuality.EASY) }

        assertEquals(SRSEngine.MAX_INTERVAL_DAYS, current.interval)
    }

    @Test
    fun theEaseNeverClimbsAboveTheCeiling() {
        var current = card()
        repeat(20) { current = engine.calculateNextReview(current, ReviewQuality.EASY) }

        assertEquals(SRSEngine.MAX_EASE_FACTOR, current.easeFactor, 0.0001f)
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

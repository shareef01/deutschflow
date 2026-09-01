package com.aus.deutschflow.service

import com.aus.deutschflow.data.local.entities.VocabularyEntity
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class ReviewQuality(val value: Int) {
    AGAIN(0),  // Failed, need to see it again immediately
    HARD(1),   // Succeeded with great difficulty
    GOOD(2),   // Succeeded with normal effort
    EASY(3)    // Succeeded effortlessly
}

@Singleton
class SRSEngine @Inject constructor() {

    /**
     * Calculates the next review state for a word based on the user's performance.
     * Implements a variation of the SuperMemo-2 (SM-2) algorithm.
     */
    fun calculateNextReview(vocab: VocabularyEntity, quality: ReviewQuality): VocabularyEntity {
        val now = System.currentTimeMillis()
        
        // SM-2 logic
        var nextEaseFactor = vocab.easeFactor
        var nextInterval: Int
        var nextReviewCount = vocab.reviewCount

        when (quality) {
            // Failed: back into this session, and the learning progress resets.
            ReviewQuality.AGAIN -> {
                nextInterval = 0
                nextEaseFactor -= AGAIN_EASE_PENALTY
                nextReviewCount = 0
            }

            // Correct, but a struggle. This is a success with a shorter next interval,
            // not a lapse: it used to set a flat 1 day, so a 95-day card dropped to
            // tomorrow and took four more reviews to climb back. That punished the
            // honest answer harder than the schedule punished a wrong one, which
            // teaches people to stop pressing the button.
            //
            // The multiplier deliberately ignores the ease factor. Ease describes how
            // hard the word is in general; Hard is a statement about this one attempt,
            // and it should shorten the step by the same proportion whatever the card's
            // history.
            ReviewQuality.HARD -> {
                nextInterval = Math.round(vocab.interval * HARD_MULTIPLIER)
                    .coerceAtLeast(vocab.interval + 1)
                    .coerceAtLeast(1)
                nextEaseFactor -= HARD_EASE_PENALTY
                nextReviewCount++
            }

            // Recalled on schedule, or effortlessly.
            ReviewQuality.GOOD, ReviewQuality.EASY -> {
                nextInterval = when (vocab.reviewCount) {
                    0 -> 1 // First successful review -> 1 day
                    1 -> 6 // Second successful review -> 6 days
                    else -> {
                        // Subsequent reviews -> prev_interval * ease.
                        //
                        // Rounded, not truncated: the web engine is documented as a
                        // mirror of this one and rounds, so 6 * 2.6 scheduled 15 days
                        // here and 16 there - the same card, two answers, on one synced
                        // library.
                        val multiplier = if (quality == ReviewQuality.EASY) EASY_BONUS else 1.0f
                        Math.round(vocab.interval * vocab.easeFactor * multiplier)
                            .coerceAtLeast(vocab.interval + 1)
                    }
                }

                // SM-2: EF' = EF + (0.1 - (5-q)*(0.08 + (5-q)*0.02)), where GOOD is q=4
                // and EASY is q=5. Good works out to exactly zero, which is SM-2 as
                // written.
                val q = if (quality == ReviewQuality.EASY) 5 else 4
                nextEaseFactor += (0.1f - (5 - q) * (0.08f + (5 - q) * 0.02f))
                nextReviewCount++
            }
        }

        // Constraints. The ceiling matters as much as the floor: without it, six Easy
        // ratings put a card three years out and eight put it fifty, so a word the
        // learner found easy early was silently retired from the library rather than
        // rehearsed. A year is the longest gap worth calling spaced repetition for
        // vocabulary, which decays.
        nextEaseFactor = nextEaseFactor.coerceIn(MIN_EASE_FACTOR, MAX_EASE_FACTOR)
        nextInterval = nextInterval.coerceAtMost(MAX_INTERVAL_DAYS)
        
        // Calculate timestamp
        // Interval 0 means "review again in this session" (handled by ViewModel)
        // Interval > 0 means "review in N days"
        val nextReviewTimestamp = if (nextInterval > 0) {
            now + TimeUnit.DAYS.toMillis(nextInterval.toLong())
        } else {
            0 // Ready immediately
        }

        return vocab.copy(
            nextReview = nextReviewTimestamp,
            interval = nextInterval,
            easeFactor = nextEaseFactor,
            reviewCount = nextReviewCount
        )
    }

    companion object {

        /** SM-2's floor. Below it a repeatedly failed card would schedule backwards. */
        const val MIN_EASE_FACTOR = 1.3f

        /**
         * The ceiling. SM-2 has none because it was designed for a lifetime of facts;
         * this app schedules vocabulary, which decays, and an uncapped ease compounds
         * into intervals no learner will ever see.
         */
        const val MAX_EASE_FACTOR = 3.0f

        /** A year. Longer than this is indistinguishable from deleting the card. */
        const val MAX_INTERVAL_DAYS = 365

        /** How far Easy stretches the interval beyond what the ease factor gives. */
        const val EASY_BONUS = 1.3f

        /**
         * Hard's step, applied to the interval alone.
         *
         * Shorter than the ease factor would give and longer than starting over, which
         * is the whole meaning of the button.
         */
        const val HARD_MULTIPLIER = 1.2f

        /**
         * Hard and Again used to share a flat -0.2, so the two buttons told the
         * scheduler nothing different about the card. These are SM-2's own deltas for
         * q=3 and, roughly, q=2 - a struggle costs less ease than a failure.
         */
        const val HARD_EASE_PENALTY = 0.15f
        const val AGAIN_EASE_PENALTY = 0.20f
    }
}

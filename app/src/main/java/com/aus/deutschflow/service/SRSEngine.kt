package com.aus.deutschflow.service

import androidx.annotation.StringRes
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class ReviewQuality(val value: Int) {
    AGAIN(0),  // Failed, need to see it again immediately
    HARD(1),   // Succeeded with great difficulty
    GOOD(2),   // Succeeded with normal effort
    EASY(3)    // Succeeded effortlessly
}

/** A string resource taking [count] as its only argument (srs_interval_soon ignores it). */
data class IntervalBadge(@StringRes val resId: Int, val count: Int)

@Singleton
class SRSEngine @Inject constructor() {

    /**
     * Calculates the next review state for a word based on the user's performance.
     * Implements a variation of the SuperMemo-2 (SM-2) algorithm.
     */
    fun calculateNextReview(
        vocab: VocabularyEntity,
        quality: ReviewQuality,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): VocabularyEntity {
        
        // SM-2 logic — computed in exact integer hundredths to match web/src/lib/ai/srs.ts
        // without IEEE-754 float/double accumulation drift across repeated reviews.
        val easeHundredths = Math.round(vocab.easeFactor.toString().toDouble() * 100.0).toInt()
        var nextEaseHundredths = easeHundredths
        var nextInterval: Int
        var nextReviewCount = vocab.reviewCount

        when (quality) {
            // Failed: back into this session, and the learning progress resets.
            ReviewQuality.AGAIN -> {
                nextInterval = 0
                nextEaseHundredths -= 20
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
                nextInterval = ((vocab.interval * 12 + 5) / 10)
                    .coerceAtLeast(vocab.interval + 1)
                    .coerceAtLeast(1)
                nextEaseHundredths -= 15
                nextReviewCount++
            }

            // Recalled on schedule, or effortlessly.
            ReviewQuality.GOOD, ReviewQuality.EASY -> {
                nextInterval = when (vocab.reviewCount) {
                    0 -> 1 // First successful review -> 1 day
                    1 -> 6 // Second successful review -> 6 days
                    else -> {
                        val multiplierTenths = if (quality == ReviewQuality.EASY) 13 else 10
                        val rounded = (vocab.interval * easeHundredths * multiplierTenths + 500) / 1000
                        rounded.coerceAtLeast(vocab.interval + 1)
                    }
                }

                // SM-2: EF' = EF + (0.1 - (5-q)*(0.08 + (5-q)*0.02)), where GOOD is q=4 (+0)
                // and EASY is q=5 (+0.10).
                if (quality == ReviewQuality.EASY) {
                    nextEaseHundredths += 10
                }
                nextReviewCount++
            }
        }

        val clampedEaseFactor = (nextEaseHundredths.coerceIn(130, 300) / 100.0).toFloat()
        nextInterval = nextInterval.coerceAtMost(MAX_INTERVAL_DAYS)
        
        // Interval 0 means "review again in this session" (handled by ViewModel);
        // interval > 0 means "due on the Nth day from today", from its first minute.
        val nextReviewTimestamp = if (nextInterval > 0) {
            dueAt(now, nextInterval, zone)
        } else {
            0 // Ready immediately
        }

        return vocab.copy(
            nextReview = nextReviewTimestamp,
            interval = nextInterval,
            easeFactor = clampedEaseFactor,
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

        /**
         * The start of the local day [intervalDays] days after [now].
         *
         * It used to be exactly now + N * 24 h, so a card answered at 22:00 with a
         * one-day interval was not due until 22:00 the next day - a learner studying
         * the next morning found it missing, and their schedule drifted later every
         * day they studied late (AUDIT_2026-10-12 F-21). An interval counts calendar
         * days, as Anki does, and the streak already does. Calendar arithmetic, not
         * 24-hour steps, so a DST change doesn't move the boundary off midnight.
         * dueAt in web/src/lib/ai/srs.ts is the mirror.
         */
        fun dueAt(now: Long, intervalDays: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                .plusDays(intervalDays.toLong())
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()

        /**
         * The learner-facing badge for an interval in days, as a string resource and
         * the number to put in it. E.g. in English 0 -> "< 10m", 1 -> "1d", 6 -> "6d",
         * 60 -> "2mo", 365 -> "1y".
         *
         * A resource rather than a String: the units used to be hardcoded in English,
         * so German learners saw "6d" and "2mo" under German buttons. Resolving it is
         * left to the UI, which has the configuration's locale. formatSrsInterval in
         * web/src/lib/ai/srs.ts mirrors these thresholds and the srs.interval* keys.
         */
        fun intervalBadge(intervalDays: Int): IntervalBadge = when {
            intervalDays <= 0 -> IntervalBadge(R.string.srs_interval_soon, 0)
            intervalDays < 30 -> IntervalBadge(R.string.srs_interval_days, intervalDays)
            intervalDays < 365 -> IntervalBadge(R.string.srs_interval_months, Math.round(intervalDays / 30f))
            else -> IntervalBadge(R.string.srs_interval_years, Math.round(intervalDays / 365f))
        }
    }
}

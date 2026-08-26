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

        if (quality.value >= ReviewQuality.GOOD.value) {
            // Success cases
            nextInterval = when (vocab.reviewCount) {
                0 -> 1 // First successful review -> 1 day
                1 -> 6 // Second successful review -> 6 days
                else -> {
                    // Subsequent reviews -> prev_interval * ease.
                    //
                    // Rounded, not truncated: the web engine is documented as a mirror
                    // of this one and rounds, so 6 * 2.6 scheduled 15 days here and 16
                    // there - the same card, two answers, on one synced library.
                    val multiplier = if (quality == ReviewQuality.EASY) 1.3f else 1.0f
                    Math.round(vocab.interval * vocab.easeFactor * multiplier)
                        .coerceAtLeast(vocab.interval + 1)
                }
            }
            
            // Adjust Ease Factor: EF' = EF + (0.1 - (5-q)*(0.08 + (5-q)*0.02))
            // In this branch, quality is either EASY (q=5) or GOOD (q=4)
            val q = if (quality == ReviewQuality.EASY) 5 else 4
            
            nextEaseFactor += (0.1f - (5 - q) * (0.08f + (5 - q) * 0.02f))
            nextReviewCount++
        } else {
            // Failure or Hard cases (below GOOD)
            nextInterval = if (quality == ReviewQuality.HARD) {
                // Hard but correct: show tomorrow, but don't reset count fully
                1
            } else {
                // Failed: show today (0 interval) or tomorrow (1 day)
                0 
            }
            
            // Penalty for struggling
            nextEaseFactor -= 0.2f
            
            // If failed, we reset the learning progress count
            if (quality == ReviewQuality.AGAIN) {
                nextReviewCount = 0
            }
        }

        // Constraints
        nextEaseFactor = nextEaseFactor.coerceAtLeast(1.3f)
        
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
}

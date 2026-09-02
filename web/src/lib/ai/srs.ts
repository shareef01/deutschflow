import { VocabularyEntry } from "@/lib/db/schema";

export enum ReviewQuality {
  AGAIN = 0,
  HARD = 1,
  GOOD = 2,
  EASY = 3,
}

/** SM-2's floor. Below it a repeatedly failed card would schedule backwards. */
export const MIN_EASE_FACTOR = 1.3;

/**
 * The ceiling. SM-2 has none because it was designed for a lifetime of facts;
 * this app schedules vocabulary, which decays, and an uncapped ease compounds
 * into intervals no learner will ever see.
 */
export const MAX_EASE_FACTOR = 3.0;

/** A year. Longer than this is indistinguishable from deleting the card. */
export const MAX_INTERVAL_DAYS = 365;

/** How far Easy stretches the interval beyond what the ease factor gives. */
export const EASY_BONUS = 1.3;

/**
 * Hard's step, applied to the interval alone.
 *
 * Shorter than the ease factor would give and longer than starting over, which
 * is the whole meaning of the button.
 */
export const HARD_MULTIPLIER = 1.2;

/**
 * Hard and Again used to share a flat -0.2, so the two buttons told the
 * scheduler nothing different about the card. These are SM-2's own deltas for
 * q=3 and, roughly, q=2 - a struggle costs less ease than a failure.
 */
export const HARD_EASE_PENALTY = 0.15;
export const AGAIN_EASE_PENALTY = 0.2;

/**
 * Calculates the next review state for a word.
 * Mirror of app/src/main/java/com/aus/deutschflow/service/SRSEngine.kt
 *
 * `Math.round` matches Kotlin's `Math.round(Float)`: both are floor(x + 0.5), so
 * the two engines agree on every half-day boundary. The shared fixture table in
 * SRSEngineTest and srs.test.ts is what keeps them agreeing.
 */
export function calculateNextReview(
  vocab: VocabularyEntry,
  quality: ReviewQuality
): VocabularyEntry {
  const now = Date.now();
  let nextEaseFactor = vocab.easeFactor;
  let nextInterval: number;
  let nextReviewCount = vocab.reviewCount;

  if (quality === ReviewQuality.AGAIN) {
    // Failed: back into this session, and the learning progress resets.
    nextInterval = 0;
    nextEaseFactor -= AGAIN_EASE_PENALTY;
    nextReviewCount = 0;
  } else if (quality === ReviewQuality.HARD) {
    // Correct, but a struggle. A success with a shorter next interval, not a
    // lapse: it used to set a flat 1 day, so a 95-day card dropped to tomorrow
    // and took four more reviews to climb back. The multiplier deliberately
    // ignores the ease factor - ease describes how hard the word is in general,
    // Hard is a statement about this one attempt.
    nextInterval = Math.max(
      1,
      vocab.interval + 1,
      Math.round(vocab.interval * HARD_MULTIPLIER)
    );
    nextEaseFactor -= HARD_EASE_PENALTY;
    nextReviewCount++;
  } else {
    // Recalled on schedule, or effortlessly.
    if (vocab.reviewCount === 0) {
      nextInterval = 1;
    } else if (vocab.reviewCount === 1) {
      nextInterval = 6;
    } else {
      const multiplier = quality === ReviewQuality.EASY ? EASY_BONUS : 1.0;
      nextInterval = Math.max(
        vocab.interval + 1,
        Math.round(vocab.interval * vocab.easeFactor * multiplier)
      );
    }

    // SM-2: EF' = EF + (0.1 - (5-q)*(0.08 + (5-q)*0.02)), where GOOD is q=4 and
    // EASY is q=5. Good works out to exactly zero, which is SM-2 as written.
    const q = quality === ReviewQuality.EASY ? 5 : 4;
    nextEaseFactor += 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02);
    nextReviewCount++;
  }

  // The ceiling matters as much as the floor: without it, six Easy ratings put a
  // card three years out and eight put it fifty, so a word the learner found easy
  // early was silently retired from the library rather than rehearsed.
  nextEaseFactor = Math.min(MAX_EASE_FACTOR, Math.max(MIN_EASE_FACTOR, nextEaseFactor));
  nextInterval = Math.min(MAX_INTERVAL_DAYS, nextInterval);

  const nextReviewTimestamp =
    nextInterval > 0 ? now + nextInterval * 24 * 60 * 60 * 1000 : 0;

  return {
    ...vocab,
    nextReview: nextReviewTimestamp,
    interval: nextInterval,
    easeFactor: nextEaseFactor,
    reviewCount: nextReviewCount,
  };
}

import { VocabularyEntry } from "@/lib/db/schema";

export enum ReviewQuality {
  AGAIN = 0,
  HARD = 1,
  GOOD = 2,
  EASY = 3,
}

/**
 * Calculates the next review state for a word.
 * Mirror of app/src/main/java/com/aus/deutschflow/service/SRSEngine.kt
 */
export function calculateNextReview(
  vocab: VocabularyEntry,
  quality: ReviewQuality
): VocabularyEntry {
  const now = Date.now();
  let nextEaseFactor = vocab.easeFactor;
  let nextInterval: number;
  let nextReviewCount = vocab.reviewCount;

  if (quality >= ReviewQuality.GOOD) {
    if (vocab.reviewCount === 0) {
      nextInterval = 1;
    } else if (vocab.reviewCount === 1) {
      nextInterval = 6;
    } else {
      const multiplier = quality === ReviewQuality.EASY ? 1.3 : 1.0;
      nextInterval = Math.max(
        vocab.interval + 1,
        Math.round(vocab.interval * vocab.easeFactor * multiplier)
      );
    }

    // quality >= GOOD here, so the SM-2 answer scale stops at 4; the unreachable
    // `: 3` arm this ternary used to carry was a drift hazard against SRSEngine.kt.
    const q = quality === ReviewQuality.EASY ? 5 : 4;

    nextEaseFactor += 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02);
    nextReviewCount++;
  } else {
    nextInterval = quality === ReviewQuality.HARD ? 1 : 0;
    nextEaseFactor -= 0.2;
    if (quality === ReviewQuality.AGAIN) {
      nextReviewCount = 0;
    }
  }

  nextEaseFactor = Math.max(1.3, nextEaseFactor);

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

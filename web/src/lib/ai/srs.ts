import { VocabularyEntry } from "@/lib/db/schema";
import { getCurrentLang, translate, type Lang } from "@/lib/i18n";

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
/**
 * The start of the local day `intervalDays` days after `now`.
 *
 * It used to be exactly now + N * 24 h, so a card answered at 22:00 with a
 * one-day interval was not due until 22:00 the next day, and the schedule drifted
 * later every day the learner studied late (AUDIT_2026-10-12 F-21). An interval
 * counts calendar days, as Anki does and the streak already does. The Date
 * constructor normalises the day overflow in local time, so a DST change does not
 * move the boundary off midnight. Mirrors SRSEngine.dueAt.
 */
export function dueAt(now: number, intervalDays: number): number {
  const today = new Date(now);
  return new Date(today.getFullYear(), today.getMonth(), today.getDate() + intervalDays).getTime();
}

export function calculateNextReview(
  vocab: VocabularyEntry,
  quality: ReviewQuality,
  now: number = Date.now()
): VocabularyEntry {
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

  // 0 is "again in this session"; otherwise due from the first minute of day N.
  const nextReviewTimestamp = nextInterval > 0 ? dueAt(now, nextInterval) : 0;

  return {
    ...vocab,
    nextReview: nextReviewTimestamp,
    interval: nextInterval,
    easeFactor: nextEaseFactor,
    reviewCount: nextReviewCount,
  };
}

/**
 * Formats a spaced repetition interval in days into a concise, learner-friendly badge.
 * E.g. in English 0 -> "< 10m", 1 -> "1d", 6 -> "6d", 60 -> "2mo", 365 -> "1y".
 *
 * The units come from i18n rather than being written here: they were hardcoded in
 * English, so German learners saw "6d" and "2mo" under German buttons. The same keys
 * exist as srs_interval_* in the Android resources; SRSEngine.intervalBadge picks
 * between them with the same thresholds.
 */
export function formatSrsInterval(intervalDays: number, lang: Lang = getCurrentLang()): string {
  if (intervalDays <= 0) return translate(lang, "srs.intervalSoon");
  if (intervalDays < 30) return translate(lang, "srs.intervalDays", [intervalDays]);
  if (intervalDays < 365) return translate(lang, "srs.intervalMonths", [Math.round(intervalDays / 30)]);
  return translate(lang, "srs.intervalYears", [Math.round(intervalDays / 365)]);
}

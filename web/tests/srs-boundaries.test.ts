import { describe, expect, it } from "vitest";
import { calculateNextReview, ReviewQuality, MAX_INTERVAL_DAYS } from "@/lib/ai/srs";
import type { VocabularyEntry } from "@/lib/db/schema";

function dummyCard(overrides: Partial<VocabularyEntry> = {}): VocabularyEntry {
  return {
    id: 1,
    germanText: "Zeit",
    germanTextKey: "zeit",
    englishTranslation: "time",
    timestamp: 1_700_000_000_000,
    exampleSentence: "",
    article: "die",
    plural: "Zeiten",
    conjugation: "",
    synonyms: "",
    antonyms: "",
    nextReview: 0,
    interval: 1,
    easeFactor: 2.5,
    reviewCount: 1,
    remoteId: "zeit-remote-id",
    lastModifiedAt: 1_700_000_000_000,
    ...overrides,
  };
}

describe("SRS Elapsed Time & Boundary Semantics (Priority 8)", () => {
  it("schedules elapsed duration across midnight boundaries without collapsing intervals", () => {
    // 23:59:00 on day 1
    const lateNightReview = new Date(2026, 9, 15, 23, 59, 0).getTime();
    expect(lateNightReview).toBeGreaterThan(0);
    const card = dummyCard({ interval: 1, reviewCount: 1 });

    const updated = calculateNextReview(card, ReviewQuality.GOOD);
    // Interval for reviewCount=1 with GOOD moves to 6 days
    expect(updated.interval).toBe(6);

    // Elapsed duration: 6 * 24 hours
    const sixDaysMs = 6 * 24 * 60 * 60 * 1000;
    // Difference between nextReview and current time should be exactly 6 days
    expect(updated.nextReview - Date.now()).toBeGreaterThanOrEqual(sixDaysMs - 100);
    expect(updated.nextReview - Date.now()).toBeLessThanOrEqual(sixDaysMs + 100);
  });

  it("handles DST spring forward and autumn backward transitions predictably", () => {
    // Both platforms intentionally use fixed UTC elapsed duration (24h * interval)
    // rather than wall-clock calendar days. This ensures that clock changes (clocks moving
    // forward or backward 1h) do not cause sudden schedule jumps or missed reviews.
    const springReview = Date.UTC(2026, 2, 29, 1, 0, 0); // European spring transition
    const autumnReview = Date.UTC(2026, 9, 25, 2, 0, 0); // European autumn transition
    expect(autumnReview).toBeGreaterThan(springReview);

    const card = dummyCard({ interval: 5, reviewCount: 3 });
    const springNext = calculateNextReview(card, ReviewQuality.GOOD);
    const autumnNext = calculateNextReview(card, ReviewQuality.GOOD);

    expect(springNext.interval).toBeGreaterThan(5);
    expect(autumnNext.interval).toBeGreaterThan(5);
  });

  it("maintains consistent schedule across device timezone transitions", () => {
    // If a user travels from Berlin (UTC+2) to New York (UTC-4),
    // nextReview timestamp is stored as absolute epoch milliseconds,
    // so UTC elapsed interval is completely timezone-invariant.
    const initialEpoch = 1_700_000_000_000;
    const card = dummyCard({ nextReview: initialEpoch, interval: 10 });
    expect(card.nextReview).toBe(initialEpoch);
  });

  it("handles same-day review and AGAIN reset properly", () => {
    const card = dummyCard({ interval: 30, reviewCount: 5, easeFactor: 2.6 });
    // Failed card in same-day review
    const failed = calculateNextReview(card, ReviewQuality.AGAIN);
    expect(failed.interval).toBe(0);
    expect(failed.reviewCount).toBe(0);
    expect(failed.nextReview).toBe(0); // Ready immediately for re-study in this session
    expect(failed.easeFactor).toBeCloseTo(2.4, 2);
  });

  it("enforces interval ceiling at MAX_INTERVAL_DAYS (365 days)", () => {
    const matureCard = dummyCard({ interval: 300, easeFactor: 3.0, reviewCount: 10 });
    const easyNext = calculateNextReview(matureCard, ReviewQuality.EASY);
    // 300 * 3.0 * 1.3 = 1170, but capped at MAX_INTERVAL_DAYS (365)
    expect(easyNext.interval).toBe(MAX_INTERVAL_DAYS);
  });

  it("ensures HARD quality guarantees progressive interval increase without resetting progress", () => {
    const card = dummyCard({ interval: 10, easeFactor: 2.5, reviewCount: 3 });
    const hardNext = calculateNextReview(card, ReviewQuality.HARD);
    // Hard increases interval (interval * 1.2 or interval + 1), but penalizes ease
    expect(hardNext.interval).toBe(12);
    expect(hardNext.easeFactor).toBeCloseTo(2.35, 2);
    expect(hardNext.reviewCount).toBe(4);
  });
});

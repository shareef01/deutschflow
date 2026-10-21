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
  it("a review just before midnight is due from the start of day N, not 24h steps later", () => {
    // 23:59 on day 1. Due times count calendar days (AUDIT_2026-10-12 F-21): this
    // used to assert now + exactly 6 * 24 h, which is what left a late-evening card
    // out of reach the next morning.
    const lateNightReview = new Date(2026, 9, 15, 23, 59, 0).getTime();
    const card = dummyCard({ interval: 1, reviewCount: 1 });

    const updated = calculateNextReview(card, ReviewQuality.GOOD, lateNightReview);
    expect(updated.interval).toBe(6);
    expect(updated.nextReview).toBe(new Date(2026, 9, 21).getTime());
    // One minute later is a new day, and so a day later due.
    const afterMidnight = calculateNextReview(card, ReviewQuality.GOOD, lateNightReview + 60_000);
    expect(afterMidnight.nextReview).toBe(new Date(2026, 9, 22).getTime());
  });

  it("handles DST spring forward and autumn backward transitions predictably", () => {
    // Calendar-day arithmetic in local time: whatever the clocks do, a due time
    // is the first minute of its day, never 23:00 or 01:00 the night before/after.
    // The runner's own zone decides whether these dates cross a DST change; the
    // Kotlin test pins Europe/Berlin explicitly.
    const card = dummyCard({ interval: 5, reviewCount: 3 });
    for (const reviewedAt of [new Date(2026, 2, 28, 20, 0), new Date(2026, 9, 24, 20, 0)]) {
      const next = calculateNextReview(card, ReviewQuality.GOOD, reviewedAt.getTime());
      expect(next.interval).toBeGreaterThan(5);
      const due = new Date(next.nextReview);
      expect([due.getHours(), due.getMinutes()]).toEqual([0, 0]);
    }
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

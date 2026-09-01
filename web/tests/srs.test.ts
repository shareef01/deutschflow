import { describe, expect, it } from "vitest";
import {
  MAX_EASE_FACTOR,
  MAX_INTERVAL_DAYS,
  ReviewQuality,
  calculateNextReview,
} from "@/lib/ai/srs";
import type { VocabularyEntry } from "@/lib/db/schema";

/**
 * The mirror of app/src/test/java/com/aus/deutschflow/service/SRSEngineTest.kt.
 *
 * Same cases, same expected numbers. The two engines are documented as 1:1 and had
 * already drifted — Kotlin truncated `interval * ease` where this one rounds — which
 * would have scheduled the same card differently on each device of one synced
 * library. Asserting the identical table on both sides is what keeps them honest.
 */

function card(over: Partial<VocabularyEntry> = {}): VocabularyEntry {
  return {
    id: 1,
    germanText: "das Haus",
    germanTextKey: "das haus",
    englishTranslation: "the house",
    timestamp: 1000,
    exampleSentence: "",
    article: "",
    plural: "",
    conjugation: "",
    synonyms: "",
    antonyms: "",
    nextReview: 0,
    interval: 0,
    easeFactor: 2.5,
    reviewCount: 0,
    remoteId: "fixture",
    lastModifiedAt: 1000,
    ...over,
  };
}

const DAY_MS = 24 * 60 * 60 * 1000;

describe("calculateNextReview — the SM-2 schedule", () => {
  it("schedules tomorrow after the first success", () => {
    const result = calculateNextReview(card(), ReviewQuality.GOOD);

    expect(result.interval).toBe(1);
    expect(result.reviewCount).toBe(1);
    // Good is SM-2's q=4, where the ease adjustment works out to exactly zero.
    expect(result.easeFactor).toBeCloseTo(2.5, 5);
  });

  it("schedules six days after the second success", () => {
    const result = calculateNextReview(card({ interval: 1, reviewCount: 1 }), ReviewQuality.GOOD);

    expect(result.interval).toBe(6);
    expect(result.reviewCount).toBe(2);
  });

  it("multiplies by the ease on later successes", () => {
    const result = calculateNextReview(
      card({ interval: 6, easeFactor: 2.5, reviewCount: 2 }),
      ReviewQuality.GOOD
    );

    expect(result.interval).toBe(15);
  });

  it("rounds a fractional interval rather than truncating it", () => {
    // 6 * 2.6 = 15.6. This is the case the two implementations disagreed on.
    const result = calculateNextReview(
      card({ interval: 6, easeFactor: 2.6, reviewCount: 3 }),
      ReviewQuality.GOOD
    );

    expect(result.interval).toBe(16);
  });

  it("stretches the interval and raises the ease on Easy", () => {
    const base = card({ interval: 10, reviewCount: 3 });
    const good = calculateNextReview(base, ReviewQuality.GOOD);
    const easy = calculateNextReview(base, ReviewQuality.EASY);

    expect(easy.interval).toBeGreaterThan(good.interval);
    expect(easy.easeFactor).toBeCloseTo(2.6, 5);
  });

  it("shortens a Hard card's interval rather than resetting it", () => {
    // 30 * 1.2 = 36. This used to return a flat 1, so a mature card lost its
    // whole schedule for an answer that was still correct.
    const result = calculateNextReview(
      card({ interval: 30, easeFactor: 2.5, reviewCount: 5 }),
      ReviewQuality.HARD
    );

    expect(result.interval).toBe(36);
    expect(result.easeFactor).toBeCloseTo(2.35, 5);
    // Hard is a success, so the streak advances.
    expect(result.reviewCount).toBe(6);
  });

  it("keeps a long streak's schedule roughly intact through one Hard", () => {
    // The regression this guards: five Good ratings reach 95 days, and one Hard
    // used to drop that to 1 and take four more reviews to climb back.
    const result = calculateNextReview(
      card({ interval: 95, easeFactor: 2.5, reviewCount: 5 }),
      ReviewQuality.HARD
    );

    expect(result.interval).toBeGreaterThanOrEqual(100);
    expect(result.interval).toBeLessThanOrEqual(120);
  });

  it("charges Hard and Again different ease", () => {
    // They shared a flat -0.2, so the two buttons told the scheduler nothing
    // different about the card.
    const base = card({ interval: 10, easeFactor: 2.5, reviewCount: 3 });
    const hard = calculateNextReview(base, ReviewQuality.HARD);
    const again = calculateNextReview(base, ReviewQuality.AGAIN);

    expect(hard.easeFactor).toBeGreaterThan(again.easeFactor);
    expect(hard.easeFactor).toBeCloseTo(2.35, 5);
    expect(again.easeFactor).toBeCloseTo(2.3, 5);
  });

  it("never lets a Hard card schedule backwards or to zero", () => {
    // A brand-new card has interval 0; 0 * 1.2 is still 0, and a card due
    // "in zero days" would read as a lapse to the due query.
    const result = calculateNextReview(card(), ReviewQuality.HARD);

    expect(result.interval).toBe(1);
    expect(result.nextReview).toBeGreaterThan(0);
  });

  it("caps the interval so a card is never scheduled out of reach", () => {
    // Nine Easy ratings used to reach 220 years, which is deletion by another
    // name. The cap is a year.
    let current = card();
    for (let i = 0; i < 10; i++) current = calculateNextReview(current, ReviewQuality.EASY);

    expect(current.interval).toBe(MAX_INTERVAL_DAYS);
    expect(current.nextReview - Date.now()).toBeLessThanOrEqual(MAX_INTERVAL_DAYS * DAY_MS);
  });

  it("never lets the ease climb above the ceiling", () => {
    let current = card();
    for (let i = 0; i < 20; i++) current = calculateNextReview(current, ReviewQuality.EASY);

    expect(current.easeFactor).toBeLessThanOrEqual(MAX_EASE_FACTOR);
    expect(current.easeFactor).toBeCloseTo(MAX_EASE_FACTOR, 5);
  });

  it("resets the card and leaves it due now on Again", () => {
    const result = calculateNextReview(
      card({ interval: 30, easeFactor: 2.5, reviewCount: 5 }),
      ReviewQuality.AGAIN
    );

    expect(result.interval).toBe(0);
    expect(result.reviewCount).toBe(0);
    // 0 is what the due-query reads as "ready now".
    expect(result.nextReview).toBe(0);
    expect(result.easeFactor).toBeCloseTo(2.3, 5);
  });

  it("never lets the ease fall below the floor", () => {
    let current = card({ interval: 5, easeFactor: 1.4, reviewCount: 3 });
    for (let i = 0; i < 5; i++) current = calculateNextReview(current, ReviewQuality.AGAIN);

    expect(current.easeFactor).toBeCloseTo(1.3, 5);
  });

  it("puts the due date the right number of days out", () => {
    const before = Date.now();
    const result = calculateNextReview(card({ interval: 1, reviewCount: 1 }), ReviewQuality.GOOD);

    expect(Math.round((result.nextReview - before) / DAY_MS)).toBe(6);
  });

  it("leaves the word and its grammar untouched", () => {
    const original = card({
      interval: 3,
      reviewCount: 2,
      article: "das",
      plural: "Häuser",
      synonyms: "Gebäude",
    });

    const result = calculateNextReview(original, ReviewQuality.GOOD);

    expect(result.germanText).toBe(original.germanText);
    expect(result.englishTranslation).toBe(original.englishTranslation);
    expect(result.article).toBe("das");
    expect(result.plural).toBe("Häuser");
    expect(result.synonyms).toBe("Gebäude");
    expect(result.id).toBe(original.id);
  });
});

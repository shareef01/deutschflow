import { describe, expect, it } from "vitest";
import {
  MAX_EASE_FACTOR,
  MAX_INTERVAL_DAYS,
  ReviewQuality,
  calculateNextReview,
  dueAt,
  formatSrsInterval,
} from "@/lib/ai/srs";
import { setCurrentLang } from "@/lib/i18n";
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
    const now = new Date(2026, 9, 1, 14, 30).getTime();
    const result = calculateNextReview(card({ interval: 1, reviewCount: 1 }), ReviewQuality.GOOD, now);

    expect(result.nextReview).toBe(new Date(2026, 9, 7).getTime());
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

/**
 * F-21 (AUDIT_2026-10-12): due times count calendar days. A card answered late in
 * the evening with a one-day interval is due from midnight, not from the same
 * minute tomorrow. Mirrors SRSEngineTest.aCardIsDueFromTheStartOfItsDay….
 * Built from local-time Date parts, so it holds in whatever zone the runner uses.
 */
describe("dueAt — due from the start of the local day", () => {
  it("a late-evening and an early-morning answer are both due from the next midnight", () => {
    const nextMidnight = new Date(2026, 9, 2).getTime();
    expect(dueAt(new Date(2026, 9, 1, 22, 0).getTime(), 1)).toBe(nextMidnight);
    expect(dueAt(new Date(2026, 9, 1, 0, 5).getTime(), 1)).toBe(nextMidnight);
    expect(dueAt(new Date(2026, 9, 1, 22, 0).getTime(), 1)).toBeLessThanOrEqual(
      new Date(2026, 9, 2, 8, 0).getTime()
    );
  });

  it("always lands on local midnight, across a year of DST changes in the runner's zone", () => {
    for (let day = 0; day < 400; day++) {
      const now = new Date(2026, 0, 1 + day, 20, 15).getTime();
      for (const interval of [1, 6, 30]) {
        const due = new Date(dueAt(now, interval));
        expect(due.getHours()).toBe(0);
        expect(due.getMinutes()).toBe(0);
      }
    }
  });
});

describe("formatSrsInterval — UI badge formatting", () => {
  // The same cases are asserted for Android in SRSEngineTest.intervalBadge*.
  it("formats immediate / zero interval as under 10m", () => {
    expect(formatSrsInterval(0, "en")).toBe("< 10m");
    expect(formatSrsInterval(-1, "en")).toBe("< 10m");
  });

  it("formats single and multi-day intervals", () => {
    expect(formatSrsInterval(1, "en")).toBe("1d");
    expect(formatSrsInterval(6, "en")).toBe("6d");
    expect(formatSrsInterval(29, "en")).toBe("29d");
  });

  it("formats multi-month and year intervals", () => {
    expect(formatSrsInterval(30, "en")).toBe("1mo");
    expect(formatSrsInterval(60, "en")).toBe("2mo");
    expect(formatSrsInterval(180, "en")).toBe("6mo");
    expect(formatSrsInterval(365, "en")).toBe("1y");
  });

  it("uses German units for German learners", () => {
    expect(formatSrsInterval(0, "de")).toBe("< 10 Min.");
    expect(formatSrsInterval(1, "de")).toBe("1 T.");
    expect(formatSrsInterval(29, "de")).toBe("29 T.");
    expect(formatSrsInterval(60, "de")).toBe("2 Mon.");
    expect(formatSrsInterval(365, "de")).toBe("1 J.");
  });

  it("follows the current UI language when none is passed", () => {
    setCurrentLang("de");
    try {
      expect(formatSrsInterval(6)).toBe("6 T.");
    } finally {
      setCurrentLang("en");
    }
    expect(formatSrsInterval(6)).toBe("6d");
  });
});

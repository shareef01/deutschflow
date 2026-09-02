import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ReviewQuality, calculateNextReview } from "@/lib/ai/srs";
import type { VocabularyEntry } from "@/lib/db/schema";

/**
 * The scheduler, asserted against the table the Kotlin suite also reads.
 *
 * SRSEngine.kt and lib/ai/srs.ts are the same algorithm written twice, by hand, in
 * two languages — and they had already drifted once without anyone noticing, because
 * "mirror of <path>" in a comment is not a check. The file is read from the Android
 * source tree rather than copied here on purpose: a copy is a third thing to keep in
 * step, and the point is to have one.
 */
const FIXTURE = JSON.parse(
  readFileSync(
    join(__dirname, "..", "..", "app", "src", "test", "resources", "srs-fixture.json"),
    "utf8"
  )
) as {
  cases: {
    name: string;
    given: { interval: number; easeFactor: number; reviewCount: number };
    rating: keyof typeof ReviewQuality;
    expect: { interval: number; easeFactor: number; reviewCount: number };
  }[];
};

function card(given: { interval: number; easeFactor: number; reviewCount: number }): VocabularyEntry {
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
    remoteId: "fixture",
    lastModifiedAt: 1000,
    ...given,
  };
}

describe("the shared SRS fixture", () => {
  it("has cases", () => {
    expect(FIXTURE.cases.length).toBeGreaterThan(0);
  });

  for (const testCase of FIXTURE.cases) {
    it(testCase.name, () => {
      const result = calculateNextReview(card(testCase.given), ReviewQuality[testCase.rating]);

      expect(result.interval).toBe(testCase.expect.interval);
      expect(result.easeFactor).toBeCloseTo(testCase.expect.easeFactor, 5);
      expect(result.reviewCount).toBe(testCase.expect.reviewCount);
    });
  }
});

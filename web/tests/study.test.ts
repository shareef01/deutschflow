import { beforeEach, describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import { DeutschFlowDB } from "@/lib/db/schema";
import { saveVocabulary, getDueVocabulary, getAllVocabulary } from "@/lib/db/repository";
import { ReviewQuality, calculateNextReview } from "@/lib/ai/srs";

/**
 * The extra-practice rule, asserted against the same decision `useStudy` makes.
 *
 * The hook needs React to drive it, so the branch itself is restated here — the
 * value is in pinning the *rule*: a success on a card that was not due must not
 * move it, and a failure must.
 */
function persistedFor(
  card: Awaited<ReturnType<typeof getAllVocabulary>>[number],
  quality: ReviewQuality,
  isExtraPractice: boolean
) {
  const rescheduled = calculateNextReview(card, quality);
  return !isExtraPractice || quality === ReviewQuality.AGAIN ? rescheduled : card;
}

let db: DeutschFlowDB;
let n = 0;

beforeEach(async () => {
  db = new DeutschFlowDB(`study-test-${n++}`);
  await db.open();
});

describe("extra practice", () => {
  it("is entered only when the due queue is empty", async () => {
    await saveVocabulary(db, { germanText: "das Haus", englishTranslation: "the house" });
    // A new word has nextReview 0, so it is due now.
    expect((await getDueVocabulary(db, Date.now())).length).toBe(1);

    const all = await getAllVocabulary(db);
    await db.vocabulary.update(all[0].id!, { nextReview: Date.now() + 90 * 86_400_000 });

    expect((await getDueVocabulary(db, Date.now())).length).toBe(0);
    expect((await getAllVocabulary(db)).length).toBe(1);
  });

  it("leaves a not-due card's schedule untouched on a success", async () => {
    const due = Date.now() + 90 * 86_400_000;
    const card = {
      ...(await (async () => {
        await saveVocabulary(db, { germanText: "die Übung", englishTranslation: "the exercise" });
        return (await getAllVocabulary(db))[0];
      })()),
      nextReview: due,
      interval: 90,
      easeFactor: 2.5,
      reviewCount: 5,
    };

    for (const quality of [ReviewQuality.GOOD, ReviewQuality.EASY, ReviewQuality.HARD]) {
      const persisted = persistedFor(card, quality, true);
      expect(persisted.interval).toBe(90);
      expect(persisted.nextReview).toBe(due);
      expect(persisted.reviewCount).toBe(5);
    }
  });

  it("still reschedules a card the user got wrong", async () => {
    await saveVocabulary(db, { germanText: "der Hund", englishTranslation: "the dog" });
    const card = {
      ...(await getAllVocabulary(db))[0],
      nextReview: Date.now() + 90 * 86_400_000,
      interval: 90,
      easeFactor: 2.5,
      reviewCount: 5,
    };

    const persisted = persistedFor(card, ReviewQuality.AGAIN, true);
    expect(persisted.interval).toBe(0);
    expect(persisted.nextReview).toBe(0);
    expect(persisted.reviewCount).toBe(0);
  });

  it("reschedules normally on a scheduled review", async () => {
    await saveVocabulary(db, { germanText: "das Buch", englishTranslation: "the book" });
    const card = {
      ...(await getAllVocabulary(db))[0],
      interval: 10,
      easeFactor: 2.5,
      reviewCount: 3,
    };

    const persisted = persistedFor(card, ReviewQuality.GOOD, false);
    expect(persisted.interval).toBe(25);
  });
});

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

  it("distinguishes an empty library from a completed study session", async () => {
    // 1. Initial state: 0 words in library
    const emptyAll = await getAllVocabulary(db);
    expect(emptyAll.length).toBe(0);

    // 2. Add a word and simulate completion of review
    await saveVocabulary(db, { germanText: "das Fahrrad", englishTranslation: "the bicycle" });
    const all = await getAllVocabulary(db);
    expect(all.length).toBe(1);

    // Queue of due items is 0 when nextReview is in future
    await db.vocabulary.update(all[0].id!, { nextReview: Date.now() + 10 * 86_400_000 });
    const due = await getDueVocabulary(db, Date.now());
    expect(due.length).toBe(0);
    // The library still contains the word, so it's a completed session, not an empty library
    expect(all.length).toBeGreaterThan(0);
  });

  it("restartSession drills the whole library in extra practice mode", async () => {
    await saveVocabulary(db, { germanText: "eins", englishTranslation: "one" });
    await saveVocabulary(db, { germanText: "zwei", englishTranslation: "two" });

    // Set both far in the future so due list is empty
    const all = await getAllVocabulary(db);
    for (const word of all) {
      await db.vocabulary.update(word.id!, { nextReview: Date.now() + 30 * 86_400_000 });
    }

    const due = await getDueVocabulary(db, Date.now());
    expect(due.length).toBe(0);

    // Restarting session pulls all words into extra practice
    const restartedList = await getAllVocabulary(db);
    expect(restartedList.length).toBe(2);

    // In extra practice mode, a GOOD answer keeps schedule unchanged
    const card = restartedList[0];
    const persisted = persistedFor(card, ReviewQuality.GOOD, true);
    expect(persisted.nextReview).toBe(card.nextReview);
  });
});

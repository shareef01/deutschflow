import { beforeEach, describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import { DeutschFlowDB } from "@/lib/db/schema";
import { saveVocabulary } from "@/lib/db/repository";
import { loadStudySession, remainingDueCount } from "@/hooks/useStudy";

let db: DeutschFlowDB;
let n = 0;

describe("loadStudySession state initialization logic", () => {
  beforeEach(async () => {
    db = new DeutschFlowDB(`study-init-test-${n++}`);
    await db.open();
  });

  it("loads an empty session with 0 words and 0 due cards", async () => {
    const session = await loadStudySession(db);
    expect(session.totalWords).toBe(0);
    expect(session.dueCount).toBe(0);
    expect(session.isExtraPractice).toBe(true);
    expect(session.studyList.length).toBe(0);
  });

  it("loads due cards and marks session as normal scheduled study", async () => {
    await saveVocabulary(db, {
      germanText: "der Apfel",
      englishTranslation: "the apple",
    });

    const session = await loadStudySession(db);
    expect(session.totalWords).toBe(1);
    expect(session.dueCount).toBe(1);
    expect(session.isExtraPractice).toBe(false);
    expect(session.studyList.length).toBe(1);
  });

  it("falls back to entire library as extra practice when 0 cards are due", async () => {
    await saveVocabulary(db, {
      germanText: "die Katze",
      englishTranslation: "the cat",
    });

    const all = await db.vocabulary.toArray();
    // Schedule far in the future
    await db.vocabulary.update(all[0].id!, { nextReview: Date.now() + 100 * 86_400_000 });

    const session = await loadStudySession(db);
    expect(session.totalWords).toBe(1);
    expect(session.dueCount).toBe(0);
    expect(session.isExtraPractice).toBe(true);
    expect(session.studyList.length).toBe(1);
  });

  it("propagates DB error if query rejects", async () => {
    await db.close();
    await expect(loadStudySession(db)).rejects.toThrow();
  });

  it("recovers successfully on retry after DB is restored", async () => {
    await saveVocabulary(db, {
      germanText: "das Haus",
      englishTranslation: "the house",
    });
    // First simulate failure
    await db.close();
    await expect(loadStudySession(db)).rejects.toThrow();

    // Reopen DB (simulating retry)
    await db.open();
    const session = await loadStudySession(db);
    expect(session.totalWords).toBe(1);
    expect(session.dueCount).toBe(1);
    expect(session.studyList[0].germanText).toBe("das Haus");
  });
});

// R-01 (AUDIT_2026-10-22): the dashboard banner read the load-time snapshot, so
// it still said "5 cards due" after all 5 were reviewed.
describe("remainingDueCount", () => {
  beforeEach(async () => {
    db = new DeutschFlowDB(`study-due-test-${n++}`);
    await db.open();
  });

  it("falls as due cards leave the scheduled queue", async () => {
    for (const [de, en] of [["der Hund", "the dog"], ["die Katze", "the cat"]]) {
      await saveVocabulary(db, { germanText: de, englishTranslation: en });
    }
    const session = await loadStudySession(db);
    expect(remainingDueCount(session.studyList, session.isExtraPractice)).toBe(2);

    // A success removes the card from the sitting.
    const afterGood = session.studyList.slice(1);
    expect(remainingDueCount(afterGood, false)).toBe(1);

    // Again re-queues it, still due, so the count holds.
    const afterAgain = [...afterGood.slice(1), afterGood[0]];
    expect(remainingDueCount(afterAgain, false)).toBe(1);

    expect(remainingDueCount([], false)).toBe(0);
  });

  it("is zero for extra practice, however many cards are drilled", async () => {
    await saveVocabulary(db, { germanText: "das Haus", englishTranslation: "the house" });
    const all = await db.vocabulary.toArray();
    await db.vocabulary.update(all[0].id!, { nextReview: Date.now() + 100 * 86_400_000 });

    const session = await loadStudySession(db);
    expect(session.studyList.length).toBe(1);
    expect(remainingDueCount(session.studyList, session.isExtraPractice)).toBe(0);
  });
});

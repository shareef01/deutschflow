import { beforeEach, describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import { DeutschFlowDB } from "@/lib/db/schema";
import { saveVocabulary } from "@/lib/db/repository";
import { loadStudySession } from "@/hooks/useStudy";

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
});

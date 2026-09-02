import { beforeEach, describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import { DeutschFlowDB } from "@/lib/db/schema";
import { exportLibrary, importLibrary } from "@/lib/db/backup";
import { saveVocabulary, insertTranscript, rewardXp } from "@/lib/db/repository";

/**
 * The round trip that stands between a user and losing everything.
 *
 * IndexedDB is the only copy of the web library, and the browser may evict it, so
 * these assertions are about data survival rather than about a feature working.
 */

let db: DeutschFlowDB;
let n = 0;

beforeEach(async () => {
  db = new DeutschFlowDB(`backup-test-${n++}`);
  await db.open();
});

describe("exportLibrary / importLibrary", () => {
  it("carries a mid-schedule card across with its SRS state intact", async () => {
    await saveVocabulary(db, { germanText: "die Übung", englishTranslation: "the exercise" });
    const saved = await db.vocabulary.toArray();
    await db.vocabulary.update(saved[0].id!, {
      nextReview: 1_900_000_000_000,
      interval: 38,
      easeFactor: 2.35,
      reviewCount: 4,
    });

    const backup = await exportLibrary(db);

    const fresh = new DeutschFlowDB(`backup-test-${n++}`);
    await fresh.open();
    const result = await importLibrary(fresh, backup);

    expect(result.vocabularyAdded).toBe(1);
    const restored = (await fresh.vocabulary.toArray())[0];
    expect(restored.germanText).toBe("die Übung");
    expect(restored.interval).toBe(38);
    expect(restored.easeFactor).toBe(2.35);
    expect(restored.reviewCount).toBe(4);
    expect(restored.nextReview).toBe(1_900_000_000_000);
  });

  it("merges into an existing library rather than replacing it", async () => {
    await saveVocabulary(db, { germanText: "das Haus", englishTranslation: "the house" });
    const backup = await exportLibrary(db);

    const other = new DeutschFlowDB(`backup-test-${n++}`);
    await other.open();
    // A word the backup does not know about must survive the restore.
    await saveVocabulary(other, { germanText: "der Hund", englishTranslation: "the dog" });

    const result = await importLibrary(other, backup);

    expect(result.vocabularyAdded).toBe(1);
    const words = (await other.vocabulary.toArray()).map((v) => v.germanText).sort();
    expect(words).toEqual(["das Haus", "der Hund"]);
  });

  it("does not duplicate anything when the same backup is imported twice", async () => {
    await saveVocabulary(db, { germanText: "das Haus", englishTranslation: "the house" });
    await insertTranscript(db, "Ich lerne Deutsch.");
    const backup = await exportLibrary(db);

    const fresh = new DeutschFlowDB(`backup-test-${n++}`);
    await fresh.open();
    await importLibrary(fresh, backup);
    await importLibrary(fresh, backup);

    expect(await fresh.vocabulary.count()).toBe(1);
    expect(await fresh.transcripts.count()).toBe(1);
  });

  it("never reduces XP or streak when an older backup is restored", async () => {
    const backup = await exportLibrary(db); // xp 0, streak 0

    const other = new DeutschFlowDB(`backup-test-${n++}`);
    await other.open();
    await rewardXp(other, 50);

    await importLibrary(other, backup);

    const stats = await other.userStats.where("id").equals(1).first();
    expect(stats?.xp).toBe(50);
  });

  it("refuses a file that is not a DeutschFlow export", async () => {
    await expect(importLibrary(db, { format: "something-else" })).rejects.toThrow();
    await expect(importLibrary(db, "not an object")).rejects.toThrow();
  });
});

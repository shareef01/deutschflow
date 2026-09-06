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

  it("rejects missing, non-number, 0, or fractional versions", async () => {
    const base = {
      format: "deutschflow-library",
      vocabulary: [],
      transcripts: [],
      userStats: [],
      activityLog: [],
    };
    await expect(importLibrary(db, { ...base })).rejects.toThrow();
    await expect(importLibrary(db, { ...base, version: 0 })).rejects.toThrow();
    await expect(importLibrary(db, { ...base, version: -1 })).rejects.toThrow();
    await expect(importLibrary(db, { ...base, version: 1.5 })).rejects.toThrow();
    await expect(importLibrary(db, { ...base, version: "1" })).rejects.toThrow();
  });

  it("rejects backups made by a newer version with reason 'newer'", async () => {
    const backup = {
      format: "deutschflow-library",
      version: 99,
      vocabulary: [],
      transcripts: [],
      userStats: [],
      activityLog: [],
    };
    await expect(importLibrary(db, backup)).rejects.toThrow(/newer/i);
  });

  it("rejects non-array collections", async () => {
    const backup = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: "not an array",
      transcripts: [],
      userStats: [],
      activityLog: [],
    };
    await expect(importLibrary(db, backup)).rejects.toThrow(/array/i);
  });

  it("rejects collections exceeding maximum row counts", async () => {
    const oversizedVocab = new Array(10_001).fill({
      germanText: "Wort",
      englishTranslation: "word",
    });
    const backup = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: oversizedVocab,
      transcripts: [],
      userStats: [],
      activityLog: [],
    };
    await expect(importLibrary(db, backup)).rejects.toThrow(/limit/i);
  });

  it("rejects giant text fields in vocabulary and transcripts", async () => {
    const giantText = "A".repeat(5_000);
    const backupVocab = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [{ germanText: "Wort", englishTranslation: giantText }],
      transcripts: [],
      userStats: [],
      activityLog: [],
    };
    await expect(importLibrary(db, backupVocab)).rejects.toThrow(/length/i);

    const giantTranscript = "T".repeat(20_000);
    const backupTranscript = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [],
      transcripts: [{ fullText: giantTranscript }],
      userStats: [],
      activityLog: [],
    };
    await expect(importLibrary(db, backupTranscript)).rejects.toThrow(/length/i);
  });

  it("rejects invalid numeric values (negative interval, interval > 365, invalid ease factor, negative XP)", async () => {
    const base = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [],
      transcripts: [],
      userStats: [],
      activityLog: [],
    };

    // Negative interval
    await expect(
      importLibrary(db, {
        ...base,
        vocabulary: [{ germanText: "Test", englishTranslation: "Test", interval: -1 }],
      })
    ).rejects.toThrow(/interval/i);

    // Interval > 365
    await expect(
      importLibrary(db, {
        ...base,
        vocabulary: [{ germanText: "Test", englishTranslation: "Test", interval: 366 }],
      })
    ).rejects.toThrow(/interval/i);

    // Ease factor out of range (< 1.3 or > 3.0)
    await expect(
      importLibrary(db, {
        ...base,
        vocabulary: [{ germanText: "Test", englishTranslation: "Test", easeFactor: 1.1 }],
      })
    ).rejects.toThrow(/easeFactor/i);
    await expect(
      importLibrary(db, {
        ...base,
        vocabulary: [{ germanText: "Test", englishTranslation: "Test", easeFactor: 3.5 }],
      })
    ).rejects.toThrow(/easeFactor/i);

    // Negative XP
    await expect(
      importLibrary(db, {
        ...base,
        userStats: [{ xp: -50, streak: 1 }],
      })
    ).rejects.toThrow(/XP/i);

    // Unsafe integer timestamp
    await expect(
      importLibrary(db, {
        ...base,
        vocabulary: [{ germanText: "Test", englishTranslation: "Test", timestamp: NaN }],
      })
    ).rejects.toThrow(/timestamp/i);
  });

  it("rejects invalid calendar dates in activity log", async () => {
    const base = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [],
      transcripts: [],
      userStats: [],
      activityLog: [],
    };

    // Non-existent dates that a naive regex might accept
    for (const badDate of ["2026-02-31", "2026-13-01", "9999-99-99", "not-a-date"]) {
      await expect(
        importLibrary(db, {
          ...base,
          activityLog: [{ date: badDate, xpGained: 10, timestamp: 1000 }],
        })
      ).rejects.toThrow(/calendar date/i);
    }
  });

  it("sanitizes invalid or missing remoteIds with a fresh UUID", async () => {
    const backup = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [],
      transcripts: [
        { fullText: "Transcript 1", remoteId: "invalid-arbitrary-string" },
        { fullText: "Transcript 2", remoteId: "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d" },
      ],
      userStats: [],
      activityLog: [],
    };

    const fresh = new DeutschFlowDB(`backup-test-${n++}`);
    await fresh.open();
    await importLibrary(fresh, backup);

    const saved = await fresh.transcripts.toArray();
    expect(saved.length).toBe(2);
    // Invalid remoteId got replaced with a valid UUID
    expect(saved[0].remoteId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
    );
    expect(saved[0].remoteId).not.toBe("invalid-arbitrary-string");
    // Valid remoteId was preserved
    expect(saved[1].remoteId).toBe("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d");
  });
});

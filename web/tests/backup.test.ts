import { beforeEach, describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import { DeutschFlowDB } from "@/lib/db/schema";
import { deterministicTranscriptId, exportLibrary, importLibrary } from "@/lib/db/backup";
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

    await expect(importLibrary(db, {
      ...backup,
      vocabulary: [],
      userStats: [{ xp: 1 }, { xp: 2 }],
    })).rejects.toThrow(/stats count exceeds limit/i);
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

    // A partial learned-state tuple would otherwise import a card that can never
    // be selected as due by the SRS engine.
    await expect(
      importLibrary(db, {
        ...base,
        vocabulary: [{
          germanText: "Test",
          englishTranslation: "Test",
          reviewCount: 1,
          interval: 0,
          nextReview: 0,
        }],
      })
    ).rejects.toThrow(/SRS schedule/i);
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

  it("is fully idempotent when importing legacy transcripts without remoteId twice", async () => {
    const backup = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [],
      transcripts: [
        { fullText: "Legacy 1", timestamp: 1000000 },
        { fullText: "Legacy 2", timestamp: 2000000 },
      ],
      userStats: [],
      activityLog: [],
    };

    const fresh = new DeutschFlowDB(`backup-test-${n++}`);
    await fresh.open();
    const firstRes = await importLibrary(fresh, backup);
    expect(firstRes.transcriptsAdded).toBe(2);

    const secondRes = await importLibrary(fresh, backup);
    expect(secondRes.transcriptsAdded).toBe(0);
    expect(await fresh.transcripts.count()).toBe(2);
  });

  it("treats streak coherently and recomputes streak from merged activity history", async () => {
    const today = new Date();
    const y = today.getFullYear();
    const m = String(today.getMonth() + 1).padStart(2, "0");
    const d = String(today.getDate()).padStart(2, "0");
    const todayStr = `${y}-${m}-${d}`;

    const backup = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [],
      transcripts: [],
      userStats: [{ xp: 100, streak: 30, lastActivityTimestamp: 5000 }],
      activityLog: [
        { date: todayStr, xpGained: 50, timestamp: Date.now() }
      ],
    };

    const fresh = new DeutschFlowDB(`backup-test-${n++}`);
    await fresh.open();
    await importLibrary(fresh, backup);

    const stats = await fresh.userStats.get(1);
    expect(stats?.xp).toBe(100);
    // Because activity history only has today active, streak is 1, not impossible 30
    expect(stats?.streak).toBe(1);
  });

  it("keeps newer local vocabulary fields and its SRS schedule when importing an older copy", async () => {
    await saveVocabulary(db, {
      germanText: "das Haus",
      englishTranslation: "the home",
      article: "das",
      exampleSentence: "Das Haus ist neu.",
      timestamp: 2_000,
    });
    const local = (await db.vocabulary.toArray())[0];
    await db.vocabulary.update(local.id!, {
      interval: 21,
      easeFactor: 2.4,
      reviewCount: 6,
      nextReview: 9_000,
    });

    await importLibrary(db, {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [{
        germanText: "das Haus",
        englishTranslation: "house (old)",
        article: "",
        exampleSentence: "Old example.",
        timestamp: 1_000,
        interval: 2,
        easeFactor: 1.5,
        reviewCount: 1,
        nextReview: 3_000,
      }],
      transcripts: [],
      userStats: [],
      activityLog: [],
    });

    const merged = (await db.vocabulary.toArray())[0];
    expect(merged.englishTranslation).toBe("the home");
    expect(merged.article).toBe("das");
    expect(merged.exampleSentence).toBe("Das Haus ist neu.");
    expect({
      interval: merged.interval,
      easeFactor: merged.easeFactor,
      reviewCount: merged.reviewCount,
      nextReview: merged.nextReview,
    }).toEqual({ interval: 21, easeFactor: 2.4, reviewCount: 6, nextReview: 9_000 });
  });

  it("keeps activity XP and timestamp from one coherent winner", async () => {
    await db.activityLog.put({ date: "2026-09-08", xpGained: 100, timestamp: 2_000 });

    await importLibrary(db, {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [],
      transcripts: [],
      userStats: [],
      activityLog: [{ date: "2026-09-08", xpGained: 50, timestamp: 9_000 }],
    });

    expect(await db.activityLog.get("2026-09-08")).toEqual({
      date: "2026-09-08",
      xpGained: 100,
      timestamp: 2_000,
    });
  });

  it("rejects missing required content before writing any valid-looking rows", async () => {
    await expect(importLibrary(db, {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [{ germanText: "der Hund", englishTranslation: "the dog" }],
      transcripts: [{ fullText: "" }],
      userStats: [],
      activityLog: [],
    })).rejects.toThrow(/Transcript text is required/);

    expect(await db.vocabulary.count()).toBe(0);
  });

  it("preserves a new vocabulary row's stable backup identity", async () => {
    const remoteId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d";
    await importLibrary(db, {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [{
        germanText: "der Hund",
        englishTranslation: "the dog",
        timestamp: 1_000,
        remoteId,
        lastModifiedAt: 1_500,
      }],
      transcripts: [],
      userStats: [],
      activityLog: [],
    });

    const saved = (await db.vocabulary.toArray())[0];
    expect(saved.remoteId).toBe(remoteId);
    expect(saved.lastModifiedAt).toBe(1_500);
  });

  describe("deterministicTranscriptId", () => {
    it("produces identical IDs for identical inputs (idempotence)", () => {
      const id1 = deterministicTranscriptId("Guten Morgen", 1600000000);
      const id2 = deterministicTranscriptId("Guten Morgen", 1600000000);
      expect(id1).toBe(id2);
      expect(id1).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    });

    it("produces different IDs when timestamp changes", () => {
      const id1 = deterministicTranscriptId("Guten Morgen", 1600000000);
      const id2 = deterministicTranscriptId("Guten Morgen", 1600000001);
      expect(id1).not.toBe(id2);
    });

    it("produces different IDs when text changes", () => {
      const id1 = deterministicTranscriptId("Guten Morgen", 1600000000);
      const id2 = deterministicTranscriptId("Guten Abend", 1600000000);
      expect(id1).not.toBe(id2);
    });

    it("does not collapse distinct legacy records", () => {
      const records = [
        { text: "Hallo Welt", time: 1000 },
        { text: "Hallo Welt 2", time: 1000 },
        { text: "Wie geht es dir?", time: 2000 },
        { text: "Ich lerne Deutsch", time: 3000 },
      ];
      const ids = new Set(records.map((r) => deterministicTranscriptId(r.text, r.time)));
      expect(ids.size).toBe(records.length);
    });

    it("normalizes trailing whitespace and CRLF newlines canonically", () => {
      const id1 = deterministicTranscriptId("  Guten Tag\nWie gehts?  ", 1000);
      const id2 = deterministicTranscriptId("Guten Tag\r\nWie gehts?", 1000);
      expect(id1).toBe(id2);
    });
  });
});

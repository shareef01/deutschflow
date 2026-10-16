import { beforeEach, describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import { DeutschFlowDB } from "@/lib/db/schema";
import {
  deterministicTranscriptId,
  exportLibrary,
  exportEncryptedLibrary,
  importLibrary,
  validateBackup,
  encryptBackup,
  decryptBackup,
  isEncryptedBackup,
  ENCRYPTED_BACKUP_FORMAT,
  ENCRYPTED_BACKUP_VERSION,
  ImportError,
  MAX_BACKUP_FILE_BYTES,
} from "@/lib/db/backup";
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

  it("uses a total byte budget rather than rejecting a long-lived library by row count", async () => {
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
    expect(() => validateBackup(backup)).not.toThrow();
    expect(() => validateBackup({ ...backup, vocabulary: [{ germanText: "Wort",
      englishTranslation: "A".repeat(MAX_BACKUP_FILE_BYTES) }] })).toThrow(/size limit/i);

    await expect(importLibrary(db, {
      ...backup,
      vocabulary: [],
      userStats: [{ xp: 1 }, { xp: 2 }],
    })).rejects.toThrow(/stats count exceeds limit/i);
  });

  it("restores legitimate long text fields from legacy backups within the byte budget", async () => {
    const giantText = "A".repeat(5_000);
    const backupVocab = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [{ germanText: "Wort", englishTranslation: giantText }],
      transcripts: [],
      userStats: [],
      activityLog: [],
    };
    await importLibrary(db, backupVocab);
    expect((await db.vocabulary.toArray())[0].englishTranslation).toBe(giantText);

    const giantTranscript = "T".repeat(20_000);
    const backupTranscript = {
      format: "deutschflow-library",
      version: 1,
      vocabulary: [],
      transcripts: [{ fullText: giantTranscript }],
      userStats: [],
      activityLog: [],
    };
    await importLibrary(db, backupTranscript);
    expect((await db.transcripts.toArray())[0].fullText).toBe(giantTranscript);
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

  describe("encrypted backup & crypto parity with Android BackupCrypto", () => {
    const validPlainPayload = JSON.stringify({
      format: "deutschflow-library",
      version: 2,
      exportedAt: "2026-10-17T00:00:00.000Z",
      vocabulary: [
        {
          id: 1,
          germanText: "der Schlüssel",
          englishTranslation: "the key",
          timestamp: 1000,
        },
      ],
      transcripts: [],
      userStats: [{ id: 1, xp: 50, streak: 3, lastActivityTimestamp: 1000 }],
      activityLog: [{ date: "2026-10-17", xpGained: 50, timestamp: 1000 }],
    });

    it("encrypts and decrypts with valid passphrase", async () => {
      const passphrase = "correct-horse-battery-staple";
      const encryptedJsonStr = await encryptBackup(validPlainPayload, passphrase);
      const envelope = JSON.parse(encryptedJsonStr);

      expect(isEncryptedBackup(envelope)).toBe(true);
      expect(envelope.format).toBe(ENCRYPTED_BACKUP_FORMAT);
      expect(envelope.version).toBe(ENCRYPTED_BACKUP_VERSION);
      expect(envelope.kdf).toBe("PBKDF2WithHmacSHA256");
      expect(envelope.iterations).toBe(100_000);
      expect(typeof envelope.salt).toBe("string");
      expect(typeof envelope.iv).toBe("string");
      expect(typeof envelope.ciphertext).toBe("string");

      const decrypted = await decryptBackup(envelope, passphrase);
      expect(decrypted).toBe(validPlainPayload);
    });

    it("throws wrong_passphrase when passphrase is wrong", async () => {
      const encryptedJsonStr = await encryptBackup(validPlainPayload, "secret-pass");
      const envelope = JSON.parse(encryptedJsonStr);

      await expect(decryptBackup(envelope, "wrong-pass")).rejects.toThrow(ImportError);
      try {
        await decryptBackup(envelope, "wrong-pass");
      } catch (e) {
        expect(e).toBeInstanceOf(ImportError);
        expect((e as ImportError).reason).toBe("wrong_passphrase");
      }
    });

    it("throws passphrase_required when passphrase is null, undefined, or empty", async () => {
      const encryptedJsonStr = await encryptBackup(validPlainPayload, "secret");
      const envelope = JSON.parse(encryptedJsonStr);

      await expect(decryptBackup(envelope, "")).rejects.toThrow(ImportError);
      await expect(decryptBackup(envelope, null)).rejects.toThrow(ImportError);
      await expect(decryptBackup(envelope, undefined)).rejects.toThrow(ImportError);
    });

    it("throws invalid for unencrypted payload passed to decryptBackup", async () => {
      const plainObj = { format: "deutschflow-library", version: 2 };
      expect(isEncryptedBackup(plainObj)).toBe(false);
      await expect(decryptBackup(plainObj, "secret")).rejects.toThrow(/Not an encrypted/);
    });

    it("throws newer for unsupported version (> 1)", async () => {
      const futureEnvelope = {
        format: ENCRYPTED_BACKUP_FORMAT,
        version: 999,
        salt: "c2FsdHNhbHQxMjM0NTY3OA==",
        iv: "aXZpdml2aXZpdg==",
        ciphertext: "Y2lwaGVydGV4dA==",
      };
      await expect(decryptBackup(futureEnvelope, "secret")).rejects.toThrow(/not supported/);
    });

    it("throws corrupted for tampered ciphertext (auth tag mismatch)", async () => {
      const encryptedJsonStr = await encryptBackup(validPlainPayload, "secret-key");
      const envelope = JSON.parse(encryptedJsonStr);

      // Tamper with the base64 ciphertext
      const tamperedBytes = Buffer.from(envelope.ciphertext, "base64");
      tamperedBytes[0] ^= 0xff; // Flip bits
      envelope.ciphertext = tamperedBytes.toString("base64");

      await expect(decryptBackup(envelope, "secret-key")).rejects.toThrow(ImportError);
    });

    it("throws corrupted for invalid salt size or invalid IV size", async () => {
      const shortSalt = {
        format: ENCRYPTED_BACKUP_FORMAT,
        version: 1,
        iterations: 100_000,
        salt: Buffer.from("tooshort").toString("base64"), // 8 bytes < 16 bytes
        iv: Buffer.from("123456789012").toString("base64"),
        ciphertext: "Y2lwaGVydGV4dA==",
      };
      await expect(decryptBackup(shortSalt, "secret")).rejects.toThrow(/salt/i);

      const badIv = {
        format: ENCRYPTED_BACKUP_FORMAT,
        version: 1,
        iterations: 100_000,
        salt: Buffer.from("1234567890123456").toString("base64"),
        iv: Buffer.from("bad_length").toString("base64"), // 10 bytes != 12 bytes
        ciphertext: "Y2lwaGVydGV4dA==",
      };
      await expect(decryptBackup(badIv, "secret")).rejects.toThrow(/IV/i);
    });

    it("throws invalid for out-of-range iterations", async () => {
      const lowIterations = {
        format: ENCRYPTED_BACKUP_FORMAT,
        version: 1,
        iterations: 1_000, // < 50,000
        salt: Buffer.from("1234567890123456").toString("base64"),
        iv: Buffer.from("123456789012").toString("base64"),
        ciphertext: "Y2lwaGVydGV4dA==",
      };
      await expect(decryptBackup(lowIterations, "secret")).rejects.toThrow(/iterations/i);

      const highIterations = {
        format: ENCRYPTED_BACKUP_FORMAT,
        version: 1,
        iterations: 1_000_000, // > 500,000
        salt: Buffer.from("1234567890123456").toString("base64"),
        iv: Buffer.from("123456789012").toString("base64"),
        ciphertext: "Y2lwaGVydGV4dA==",
      };
      await expect(decryptBackup(highIterations, "secret")).rejects.toThrow(/iterations/i);
    });

    it("full round trip: exportEncryptedLibrary -> importLibrary with passphrase", async () => {
      await saveVocabulary(db, { germanText: "die Freiheit", englishTranslation: "freedom" });
      await insertTranscript(db, "Wir lernen Deutsch zusammen.");
      await rewardXp(db, 30);

      const passphrase = "deutschflow-secure-phrase-2026";
      const encryptedJson = await exportEncryptedLibrary(db, passphrase);

      const targetDb = new DeutschFlowDB(`backup-test-${n++}`);
      await targetDb.open();

      // Attempting to restore without passphrase must fail with passphrase_required
      await expect(importLibrary(targetDb, encryptedJson)).rejects.toThrow(ImportError);

      // Attempting to restore with wrong passphrase must fail with wrong_passphrase
      await expect(importLibrary(targetDb, encryptedJson, "incorrect-pass")).rejects.toThrow(ImportError);
      expect(await targetDb.vocabulary.count()).toBe(0);

      // Restoring with correct passphrase succeeds
      const result = await importLibrary(targetDb, encryptedJson, passphrase);
      expect(result.vocabularyAdded).toBe(1);
      expect(result.transcriptsAdded).toBe(1);

      const savedWord = (await targetDb.vocabulary.toArray())[0];
      expect(savedWord.germanText).toBe("die Freiheit");
      expect(savedWord.englishTranslation).toBe("freedom");

      const stats = await targetDb.userStats.get(1);
      expect(stats?.xp).toBe(30);
    });
  });
});


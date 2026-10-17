import { beforeEach, describe, expect, it } from "vitest";
import { readFileSync } from "fs";
import { join } from "path";
import { DeutschFlowDB, foldGermanKey, germanMatchKey } from "@/lib/db/schema";
import {
  importLibrary,
  exportLibrary,
  encryptBackup,
  decryptBackup,
  ImportError,
} from "@/lib/db/backup";

interface BackupVectors {
  passphrase: string;
  wrongPassphrase: string;
  androidPlaintext: Record<string, unknown>;
  androidPlainJson: string;
  androidEncrypted: Record<string, unknown>;
  androidEncryptedJson: string;
  webPlaintext: Record<string, unknown>;
  webPlainJson: string;
  webEncrypted: Record<string, unknown>;
  webEncryptedJson: string;
  corruptedEncrypted: Record<string, unknown>;
  corruptedEncryptedJson: string;
}

const vectors: BackupVectors = JSON.parse(
  readFileSync(
    join(__dirname, "..", "..", "app", "src", "test", "resources", "cross-platform-backup-vectors.json"),
    "utf-8"
  )
);

describe("Cross-Platform Encrypted & Plaintext Backup Compatibility", () => {
  let db: DeutschFlowDB;
  let counter = 0;

  beforeEach(() => {
    db = new DeutschFlowDB(`cross-platform-backup-${counter++}`);
  });

  it("imports plaintext Android export into Web Dexie library", async () => {
    const result = await importLibrary(db, vectors.androidPlaintext);
    expect(result.vocabularyAdded).toBe(3);
    expect(result.transcriptsAdded).toBe(1);
    expect(result.reviewEventsAdded).toBe(3);

    // Verify German special characters and Unicode preservation
    const vocab = await db.vocabulary.toArray();
    expect(vocab).toHaveLength(3);
    const words = vocab.map((v) => v.germanText);
    expect(words).toContain("die Straße");
    expect(words).toContain("groß");
    expect(words).toContain("süß");

    // Verify Stats preservation (XP and streak)
    const stats = await db.userStats.get(1);
    expect(stats).toBeDefined();
    expect(stats?.xp).toBe(350);
    expect(stats?.streak).toBe(8);

    // Verify Activity Log preservation
    const activity = await db.activityLog.toArray();
    expect(activity).toHaveLength(8);

    // Verify Vocabulary ID remapping and review event linkage
    const events = await db.reviewEvents.toArray();
    expect(events).toHaveLength(3);

    const strasse = vocab.find((v) => v.germanText === "die Straße")!;
    const strasseEvent = events.find((e) => e.remoteId === "a5555555-5555-4555-8555-555555555555")!;
    expect(strasseEvent).toBeDefined();
    expect(strasseEvent.vocabularyId).toBe(strasse.id);
    expect(strasseEvent.rating).toBe("GOOD");
  });

  it("decrypts and imports encrypted Android export on Web", async () => {
    const decryptedJson = await decryptBackup(vectors.androidEncrypted, vectors.passphrase);
    expect(decryptedJson).toBe(vectors.androidPlainJson);

    const parsed = JSON.parse(decryptedJson);
    const result = await importLibrary(db, parsed);
    expect(result.vocabularyAdded).toBe(3);

    const vocab = await db.vocabulary.toArray();
    expect(vocab.map((v) => v.germanText)).toContain("die Straße");
  });

  it("decrypts encrypted Web export with matching passphrase", async () => {
    const decryptedJson = await decryptBackup(vectors.webEncrypted, vectors.passphrase);
    expect(decryptedJson).toBe(vectors.webPlainJson);

    const parsed = JSON.parse(decryptedJson);
    const result = await importLibrary(db, parsed);
    expect(result.vocabularyAdded).toBe(2);
    expect(result.reviewEventsAdded).toBe(2);

    const vocab = await db.vocabulary.toArray();
    expect(vocab.map((v) => v.germanText)).toContain("die Brücke");
    expect(vocab.map((v) => v.germanText)).toContain("Fußball");
  });

  it("rejects decryption when wrong passphrase is provided", async () => {
    await expect(
      decryptBackup(vectors.androidEncrypted, vectors.wrongPassphrase)
    ).rejects.toThrow(ImportError);

    try {
      await decryptBackup(vectors.androidEncrypted, vectors.wrongPassphrase);
    } catch (err: unknown) {
      expect((err as ImportError).reason).toBe("wrong_passphrase");
    }
  });

  it("rejects decryption when ciphertext has been corrupted/tampered", async () => {
    await expect(
      decryptBackup(vectors.corruptedEncrypted, vectors.passphrase)
    ).rejects.toThrow(ImportError);
  });

  it("repeated import is strictly idempotent and does not duplicate records", async () => {
    // First import
    const result1 = await importLibrary(db, vectors.androidPlaintext);
    expect(result1.vocabularyAdded).toBe(3);
    expect(result1.vocabularyMerged).toBe(0);

    // Second import of identical backup
    const result2 = await importLibrary(db, vectors.androidPlaintext);
    expect(result2.vocabularyAdded).toBe(0);
    expect(result2.vocabularyMerged).toBe(3);
    expect(result2.transcriptsAdded).toBe(0);
    expect(result2.reviewEventsAdded).toBe(0);

    // Database counts remain identical
    expect(await db.vocabulary.count()).toBe(3);
    expect(await db.transcripts.count()).toBe(1);
    expect(await db.reviewEvents.count()).toBe(3);
    expect(await db.activityLog.count()).toBe(8);
    expect(await db.roleplayMessages.count()).toBe(2);
  });

  it("handles unresolvable review events by assigning null vocabularyId instead of fake 0", async () => {
    // Construct backup containing an orphaned review event (e.g. from a card deleted before export)
    const backupWithOrphan = {
      format: "deutschflow-library",
      version: 2,
      exportedAt: new Date().toISOString(),
      vocabulary: [
        {
          id: 1,
          germanText: "der Wald",
          germanTextKey: "der wald",
          englishTranslation: "the forest",
          timestamp: 1000,
          exampleSentence: "",
          article: "der",
          plural: "Wälder",
          conjugation: "",
          synonyms: "",
          antonyms: "",
          nextReview: 2000,
          interval: 1,
          easeFactor: 2.5,
          reviewCount: 1,
          remoteId: "c1111111-1111-4111-8111-111111111111",
          lastModifiedAt: 1000,
        },
      ],
      transcripts: [],
      userStats: [{ id: 1, xp: 50, streak: 1, lastActivityTimestamp: 1000 }],
      activityLog: [{ date: "2026-10-17", xpGained: 50, timestamp: 1000 }],
      roleplayMessages: [],
      reviewEvents: [
        {
          // Valid resolvable event
          vocabularyId: 1,
          rating: "GOOD",
          scheduledDays: 1,
          actualDays: 1,
          reviewedAtTimestamp: 1000,
          isExtraPractice: false,
          remoteId: "e1111111-1111-4111-8111-111111111111",
        },
        {
          // Orphaned event referencing non-existent vocabulary ID 9999
          vocabularyId: 9999,
          rating: "AGAIN",
          scheduledDays: 0,
          actualDays: 0,
          reviewedAtTimestamp: 900,
          isExtraPractice: false,
          remoteId: "e2222222-2222-4222-8222-222222222222",
        },
        {
          // Legacy event with explicit null / 0 vocabularyId
          vocabularyId: null,
          rating: "EASY",
          scheduledDays: 4,
          actualDays: 4,
          reviewedAtTimestamp: 800,
          isExtraPractice: false,
          remoteId: "e3333333-3333-4333-8333-333333333333",
        },
      ],
    };

    const result = await importLibrary(db, backupWithOrphan);
    expect(result.vocabularyAdded).toBe(1);
    // All 3 events must be preserved; NONE silently discarded!
    expect(result.reviewEventsAdded).toBe(3);

    const events = await db.reviewEvents.toArray();
    expect(events).toHaveLength(3);

    const resolvable = events.find((e) => e.remoteId === "e1111111-1111-4111-8111-111111111111")!;
    expect(resolvable.vocabularyId).not.toBeNull();
    expect(resolvable.vocabularyId).toBeGreaterThan(0);

    const orphaned = events.find((e) => e.remoteId === "e2222222-2222-4222-8222-222222222222")!;
    // Principled policy: unresolvable events map to null, NOT fake ID 0!
    expect(orphaned.vocabularyId).toBeNull();

    const nullableLegacy = events.find((e) => e.remoteId === "e3333333-3333-4333-8333-333333333333")!;
    expect(nullableLegacy.vocabularyId).toBeNull();
  });

  it("independently validates canonical German fold identity and distinctions (Maße vs Masse)", () => {
    // Sharp S must NOT fold to ss for identity to prevent destructive collision
    expect(foldGermanKey("Maße")).toBe("maße");
    expect(foldGermanKey("Masse")).toBe("masse");
    expect(foldGermanKey("Maße")).not.toBe(foldGermanKey("Masse"));

    expect(foldGermanKey("Buße")).toBe("buße");
    expect(foldGermanKey("Busse")).toBe("busse");
    expect(foldGermanKey("Buße")).not.toBe(foldGermanKey("Busse"));

    expect(foldGermanKey("Straße")).toBe("straße");
    expect(foldGermanKey("Strasse")).toBe("strasse");
    expect(foldGermanKey("Straße")).not.toBe(foldGermanKey("Strasse"));

    // Loose match key folds ß to ss for search/grading
    expect(germanMatchKey("Straße")).toBe(germanMatchKey("Strasse"));
    expect(germanMatchKey("Maße")).toBe(germanMatchKey("Masse"));

    // Verify all fixture vocabulary items match foldGermanKey canonical rule
    const androidVocab = (vectors.androidPlaintext.vocabulary as Array<{ germanText: string; germanTextKey: string }>);
    for (const v of androidVocab) {
      expect(foldGermanKey(v.germanText)).toBe(v.germanTextKey);
    }

    const webVocab = (vectors.webPlaintext.vocabulary as Array<{ germanText: string; germanTextKey: string }>);
    for (const v of webVocab) {
      expect(foldGermanKey(v.germanText)).toBe(v.germanTextKey);
    }
  });

  it("normalizes composed (NFC) and decomposed (NFD) German Unicode characters identically", () => {
    // NFD: base character + combining diaeresis \u0308
    const nfdUeber = "u\u0308ber";
    const nfcUeber = "über";
    expect(foldGermanKey(nfdUeber)).toBe("ueber");
    expect(foldGermanKey(nfcUeber)).toBe("ueber");
    expect(foldGermanKey(nfdUeber)).toBe(foldGermanKey(nfcUeber));

    const nfdAerger = "A\u0308rger";
    const nfcAerger = "Ärger";
    expect(foldGermanKey(nfdAerger)).toBe("aerger");
    expect(foldGermanKey(nfcAerger)).toBe("aerger");
    expect(foldGermanKey(nfdAerger)).toBe(foldGermanKey(nfcAerger));

    const nfdOel = "O\u0308l";
    const nfcOel = "Öl";
    expect(foldGermanKey(nfdOel)).toBe("oel");
    expect(foldGermanKey(nfcOel)).toBe("oel");
    expect(foldGermanKey(nfdOel)).toBe(foldGermanKey(nfcOel));

    // Case normalization
    expect(foldGermanKey("groß")).toBe("groß");
    expect(foldGermanKey("Groß")).toBe("groß");
    expect(foldGermanKey("GROSS")).toBe("gross");
  });

  it("verifies review event references survive import, merge, export, and restore round-trips", async () => {
    // 1. Initial import of Android backup into db1
    const result1 = await importLibrary(db, vectors.androidPlaintext);
    expect(result1.vocabularyAdded).toBe(3);
    expect(result1.reviewEventsAdded).toBe(3);

    // 2. Export db1 to full LibraryBackup format
    const exportedBackup = await exportLibrary(db);
    expect(exportedBackup.vocabulary).toHaveLength(3);
    expect(exportedBackup.reviewEvents).toHaveLength(3);

    // 3. Restore exported backup into fresh db2
    const db2 = new DeutschFlowDB(`cross-platform-backup-${counter++}`);
    const result2 = await importLibrary(db2, exportedBackup);
    expect(result2.vocabularyAdded).toBe(3);
    expect(result2.reviewEventsAdded).toBe(3);

    const db2Vocab = await db2.vocabulary.toArray();
    const db2Events = await db2.reviewEvents.toArray();
    expect(db2Vocab).toHaveLength(3);
    expect(db2Events).toHaveLength(3);

    const strasse = db2Vocab.find((v) => v.germanText === "die Straße")!;
    expect(strasse).toBeDefined();

    const strasseEvent = db2Events.find((e) => e.remoteId === "a5555555-5555-4555-8555-555555555555")!;
    expect(strasseEvent).toBeDefined();
    expect(strasseEvent.vocabularyId).toBe(strasse.id);
  });

  it("preserves untrimmed passphrases with leading or trailing whitespace without modification", async () => {
    const rawPassphrase = "  passphrase with leading and trailing spaces  ";
    const wrongPassphrase = "passphrase with leading and trailing spaces"; // trimmed

    const plainBackup = await exportLibrary(db);
    const plainJson = JSON.stringify(plainBackup);

    const encryptedJson = await encryptBackup(plainJson, rawPassphrase);
    const envelope = JSON.parse(encryptedJson);

    // Decrypting with trimmed passphrase must be rejected
    await expect(decryptBackup(envelope, wrongPassphrase)).rejects.toThrow(ImportError);

    // Decrypting with exact untrimmed passphrase must succeed
    const decryptedJson = await decryptBackup(envelope, rawPassphrase);
    expect(JSON.parse(decryptedJson)).toEqual(plainBackup);
  });
});

import { beforeEach, describe, expect, it } from "vitest";
import { readFileSync } from "fs";
import { join } from "path";
import { DeutschFlowDB } from "@/lib/db/schema";
import {
  importLibrary,
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
});

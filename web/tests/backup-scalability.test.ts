import { beforeEach, describe, expect, it } from "vitest";
import { DeutschFlowDB } from "@/lib/db/schema";
import {
  exportLibrary,
  exportEncryptedLibrary,
  importLibrary,
  decryptBackup,
  MAX_PLAINTEXT_BACKUP_BYTES,
  MAX_ENCRYPTED_BACKUP_FILE_BYTES,
} from "@/lib/db/backup";

describe("Backup Scalability — Datasets Exceeding 10 MiB (up to 20+ MiB)", () => {
  let db: DeutschFlowDB;
  let targetDb: DeutschFlowDB;
  let counter = 0;

  beforeEach(() => {
    db = new DeutschFlowDB(`scalability-source-${counter}`);
    targetDb = new DeutschFlowDB(`scalability-target-${counter++}`);
  });

  it("exports, encrypts, decrypts, and restores a representative dataset exceeding 20 MiB", async () => {
    const VOCAB_COUNT = 9_000;
    const TRANSCRIPT_COUNT = 5_000;

    // Build substantial entries with realistic German text
    const sampleParagraph =
      "Der Bundesgerichtshof hat in mehreren grundlegenden Entscheidungen zur Rechtsstellung der Beteiligten im Verwaltungsverfahren Stellung genommen. Insbesondere wurde hervorgehoben, dass die Verhältnismäßigkeit aller behördlichen Maßnahmen stets zu gewährleisten ist und das Übermaßverbot als Ausprägung des Rechtsstaatsprinzips strikt beachtet werden muss. ";
    const longSampleText = sampleParagraph.repeat(3);

    const vocabBatch = [];
    for (let i = 1; i <= VOCAB_COUNT; i++) {
      vocabBatch.push({
        germanText: `Fachbegriff_${i}`,
        germanTextKey: `fachbegriff_${i}`,
        englishTranslation: `technical term ${i} for advanced administration and jurisprudence`,
        timestamp: 1729123200000,
        exampleSentence: `${longSampleText} Beispiel Nummer ${i}.`,
        article: i % 3 === 0 ? "der" : i % 3 === 1 ? "die" : "das",
        plural: `Fachbegriffe_${i}`,
        conjugation: `konjugiert_${i}`,
        synonyms: `Terminus_${i}, Wort_${i}, Ausdruck_${i}`,
        antonyms: `Umgangssprache_${i}`,
        nextReview: 1729209600000 + i * 1000,
        interval: 1 + (i % 30),
        easeFactor: 2.5,
        reviewCount: 1 + (i % 5),
        remoteId: `10000000-0000-4000-8000-${i.toString(16).padStart(12, "0")}`,
        lastModifiedAt: 1729123200000,
      });
    }

    const transcriptBatch = [];
    for (let i = 1; i <= TRANSCRIPT_COUNT; i++) {
      transcriptBatch.push({
        fullText: `${longSampleText} Transkript-Segment Nummer ${i}. Wiederholung für Aussprache und Sprachverständnis.`,
        timestamp: 1729123200000 + i * 100,
        remoteId: `20000000-0000-4000-8000-${i.toString(16).padStart(12, "0")}`,
        lastModifiedAt: 1729123200000,
        translation: `Federal Court of Justice decision transcript segment ${i}.`,
        analysisJson: JSON.stringify({
          complexity: "C1",
          segment: i,
          entities: ["Bundesgerichtshof", "Rechtsstaatsprinzip", "Übermaßverbot"],
        }),
      });
    }

    // Populate source DB in chunks
    const CHUNK = 500;
    for (let i = 0; i < vocabBatch.length; i += CHUNK) {
      await db.vocabulary.bulkAdd(vocabBatch.slice(i, i + CHUNK));
    }
    for (let i = 0; i < transcriptBatch.length; i += CHUNK) {
      await db.transcripts.bulkAdd(transcriptBatch.slice(i, i + CHUNK));
    }

    await db.userStats.put({
      id: 1,
      xp: 15_000,
      streak: 45,
      lastActivityTimestamp: 1729123200000,
    });

    // 1. Export plaintext backup
    const backup = await exportLibrary(db);
    const plainJson = JSON.stringify(backup);
    const plainByteLength = Buffer.byteLength(plainJson, "utf8");

    // Verify dataset size genuinely exceeds 10 MiB (around 20+ MiB)
    expect(plainByteLength).toBeGreaterThan(20 * 1024 * 1024);
    expect(plainByteLength).toBeLessThan(MAX_PLAINTEXT_BACKUP_BYTES);

    // 2. Export encrypted backup
    const passphrase = "TestScalabilityPassphrase2026!";
    const encryptedJson = await exportEncryptedLibrary(db, passphrase);
    const encryptedByteLength = Buffer.byteLength(encryptedJson, "utf8");

    expect(encryptedByteLength).toBeGreaterThan(plainByteLength);
    expect(encryptedByteLength).toBeLessThan(MAX_ENCRYPTED_BACKUP_FILE_BYTES);

    // 3. Decrypt the 20+ MiB encrypted backup
    const parsedEnvelope = JSON.parse(encryptedJson);
    const decryptedJson = await decryptBackup(parsedEnvelope, passphrase);
    expect(Buffer.byteLength(decryptedJson, "utf8")).toBeGreaterThan(20 * 1024 * 1024);

    // 4. Import the 20+ MiB backup into targetDb using chunked bulk operations
    const decryptedBackup = JSON.parse(decryptedJson);
    expect(decryptedBackup.vocabulary).toHaveLength(VOCAB_COUNT);
    expect(decryptedBackup.transcripts).toHaveLength(TRANSCRIPT_COUNT);
    const result = await importLibrary(targetDb, decryptedBackup);

    expect(result.vocabularyAdded).toBe(VOCAB_COUNT);
    expect(result.transcriptsAdded).toBe(TRANSCRIPT_COUNT);

    // Verify target DB data integrity
    expect(await targetDb.vocabulary.count()).toBe(VOCAB_COUNT);
    expect(await targetDb.transcripts.count()).toBe(TRANSCRIPT_COUNT);

    const firstWord = await targetDb.vocabulary.where("germanTextKey").equals("fachbegriff_1").first();
    expect(firstWord).toBeDefined();
    expect(firstWord?.englishTranslation).toContain("technical term 1");

    const stats = await targetDb.userStats.get(1);
    expect(stats?.xp).toBe(15_000);
  }, 120_000); // 2 minute timeout for 20+ MiB encryption/decryption/storage
});

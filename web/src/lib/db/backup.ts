import type { DeutschFlowDB } from "./schema";
import { foldGermanKey } from "./schema";
import { saveVocabulary } from "./repository";

/**
 * Export and import for the whole library.
 *
 * This exists because IndexedDB is the ONLY copy of everything the user has built
 * — vocabulary, transcripts, XP, streak, the activity heatmap — and the browser is
 * entitled to throw it away. Safari evicts non-installed PWAs after seven days of
 * non-use; Chrome evicts under storage pressure; clearing site data takes it; a new
 * browser or device starts empty. The Android app is backed by Room on the device's
 * own filesystem and by Android's backup service, so it has never needed this.
 *
 * Settings, deliberately, are NOT exported. The only interesting one is the API key
 * ciphertext, and it is undecryptable anywhere but the browser that wrote it — the
 * vault key is non-extractable and stays in its own IndexedDB. Carrying bytes
 * nothing can read into a backup file is worse than leaving them out: it looks like
 * the key travelled when it did not.
 */

/** Bumped only if the shape changes in a way an older import cannot read. */
export const BACKUP_VERSION = 1;

export interface LibraryBackup {
  format: "deutschflow-library";
  version: number;
  exportedAt: string;
  vocabulary: unknown[];
  transcripts: unknown[];
  userStats: unknown[];
  activityLog: unknown[];
}

export async function exportLibrary(db: DeutschFlowDB): Promise<LibraryBackup> {
  const [vocabulary, transcripts, userStats, activityLog] = await Promise.all([
    db.vocabulary.toArray(),
    db.transcripts.toArray(),
    db.userStats.toArray(),
    db.activityLog.toArray(),
  ]);

  return {
    format: "deutschflow-library",
    version: BACKUP_VERSION,
    exportedAt: new Date().toISOString(),
    vocabulary,
    transcripts,
    userStats,
    activityLog,
  };
}

export interface ImportResult {
  vocabularyAdded: number;
  vocabularyMerged: number;
  transcriptsAdded: number;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function str(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function num(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

/**
 * Merges a backup into the current library.
 *
 * Additive, never destructive: importing into a library that already has words
 * folds them together rather than replacing, so restoring an old backup onto a
 * newer device cannot lose the words added since. Vocabulary goes through
 * [saveVocabulary], which is the one path that resolves a `germanTextKey`
 * collision — so an imported duplicate merges under exactly the rule the app
 * applies when the same word is saved twice.
 *
 * A row's SRS schedule rides along with it only when the word is new here; an
 * existing row keeps its own, because the schedule on this device reflects reviews
 * this device actually saw.
 */
export async function importLibrary(
  db: DeutschFlowDB,
  backup: unknown
): Promise<ImportResult> {
  if (!isRecord(backup) || backup.format !== "deutschflow-library") {
    throw new Error("That file is not a DeutschFlow library export.");
  }
  if (num(backup.version, 0) > BACKUP_VERSION) {
    throw new Error("That backup was made by a newer version of DeutschFlow.");
  }

  const result: ImportResult = {
    vocabularyAdded: 0,
    vocabularyMerged: 0,
    transcriptsAdded: 0,
  };

  const vocabulary = Array.isArray(backup.vocabulary) ? backup.vocabulary : [];
  for (const row of vocabulary) {
    if (!isRecord(row)) continue;
    const germanText = str(row.germanText).trim();
    const englishTranslation = str(row.englishTranslation).trim();
    if (!germanText || !englishTranslation) continue;

    const existing = await db.vocabulary
      .where("germanTextKey")
      .equals(foldGermanKey(germanText))
      .first();

    await saveVocabulary(db, {
      germanText,
      englishTranslation,
      timestamp: num(row.timestamp, Date.now()),
      exampleSentence: str(row.exampleSentence),
      article: str(row.article),
      plural: str(row.plural),
      conjugation: str(row.conjugation),
      synonyms: str(row.synonyms),
      antonyms: str(row.antonyms),
    });

    if (existing) {
      result.vocabularyMerged++;
    } else {
      result.vocabularyAdded++;
      // A word this device has never seen keeps the schedule it arrived with, so a
      // restore does not reset months of reviews back to new.
      const added = await db.vocabulary
        .where("germanTextKey")
        .equals(foldGermanKey(germanText))
        .first();
      if (added?.id !== undefined) {
        await db.vocabulary.update(added.id, {
          nextReview: num(row.nextReview, 0),
          interval: num(row.interval, 0),
          easeFactor: num(row.easeFactor, 2.5),
          reviewCount: num(row.reviewCount, 0),
        });
      }
    }
  }

  const transcripts = Array.isArray(backup.transcripts) ? backup.transcripts : [];
  if (transcripts.length > 0) {
    // Deduplicated on remoteId, which is stable across devices — re-importing the
    // same backup twice must not double the history.
    const known = new Set((await db.transcripts.toArray()).map((t) => t.remoteId));
    for (const row of transcripts) {
      if (!isRecord(row)) continue;
      const fullText = str(row.fullText);
      if (!fullText) continue;
      const remoteId = str(row.remoteId) || crypto.randomUUID();
      if (known.has(remoteId)) continue;
      known.add(remoteId);
      await db.transcripts.add({
        fullText,
        timestamp: num(row.timestamp, Date.now()),
        remoteId,
        lastModifiedAt: num(row.lastModifiedAt, Date.now()),
      });
      result.transcriptsAdded++;
    }
  }

  // Stats are a high-water mark rather than a replacement: importing an older
  // backup must not reduce the XP or streak this device has since earned.
  const stats = Array.isArray(backup.userStats) ? backup.userStats : [];
  const incoming = stats.find(isRecord);
  if (incoming) {
    const current = await db.userStats.where("id").equals(1).first();
    await db.userStats.put({
      id: 1,
      xp: Math.max(current?.xp ?? 0, num(incoming.xp, 0)),
      streak: Math.max(current?.streak ?? 0, num(incoming.streak, 0)),
      lastActivityTimestamp: Math.max(
        current?.lastActivityTimestamp ?? 0,
        num(incoming.lastActivityTimestamp, 0)
      ),
    });
  }

  const activity = Array.isArray(backup.activityLog) ? backup.activityLog : [];
  for (const row of activity) {
    if (!isRecord(row)) continue;
    const date = str(row.date);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) continue;
    const existing = await db.activityLog.get(date);
    // Same high-water rule, per day.
    await db.activityLog.put({
      date,
      xpGained: Math.max(existing?.xpGained ?? 0, num(row.xpGained, 0)),
      timestamp: num(row.timestamp, Date.now()),
    });
  }

  return result;
}

/**
 * Asks the browser to stop treating this origin's storage as disposable.
 *
 * Best-effort by design: Chrome grants it silently for an installed PWA, Firefox
 * prompts, Safari ignores it. Called after the first write rather than on load, so
 * a browser that does prompt does it at a moment the user is doing something worth
 * keeping.
 */
export async function requestPersistentStorage(): Promise<boolean> {
  try {
    if (typeof navigator === "undefined" || !navigator.storage?.persist) return false;
    if (await navigator.storage.persisted()) return true;
    return await navigator.storage.persist();
  } catch {
    return false;
  }
}

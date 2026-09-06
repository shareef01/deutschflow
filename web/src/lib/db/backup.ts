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
export const MAX_BACKUP_FILE_BYTES = 10 * 1024 * 1024; // 10 MB

export const MAX_VOCABULARY_ROWS = 10_000;
export const MAX_TRANSCRIPT_ROWS = 5_000;
export const MAX_ACTIVITY_ROWS = 1_000;
export const MAX_USER_STATS_ROWS = 10;

export const MAX_FIELD_LENGTH = 2_000;
export const MAX_SHORT_FIELD_LENGTH = 200;
export const MAX_TRANSCRIPT_LENGTH = 10_000;
export const MAX_REMOTE_ID_LENGTH = 100;

const MAX_FUTURE_TIMESTAMP = 4_102_444_800_000; // 2100-01-01T00:00:00Z
const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isValidCalendarDate(dateStr: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateStr)) return false;
  const [yStr, mStr, dStr] = dateStr.split("-");
  const year = Number.parseInt(yStr, 10);
  const month = Number.parseInt(mStr, 10);
  const day = Number.parseInt(dStr, 10);
  if (year < 2000 || year > 2100) return false;
  if (month < 1 || month > 12) return false;
  if (day < 1 || day > 31) return false;
  const d = new Date(Date.UTC(year, month - 1, day));
  return (
    d.getUTCFullYear() === year &&
    d.getUTCMonth() === month - 1 &&
    d.getUTCDate() === day
  );
}

export function sanitizeRemoteId(value: unknown): string {
  const s = str(value).trim();
  if (UUID_REGEX.test(s)) {
    return s.toLowerCase();
  }
  return crypto.randomUUID();
}

/**
 * Why a restore failed, in a form the caller can translate.
 */
export type ImportFailure = "invalid" | "newer" | "storage";

export class ImportError extends Error {
  constructor(readonly reason: ImportFailure, message: string) {
    super(message);
    this.name = "ImportError";
  }
}

export async function importLibrary(
  db: DeutschFlowDB,
  backup: unknown
): Promise<ImportResult> {
  if (!isRecord(backup) || backup.format !== "deutschflow-library") {
    throw new ImportError("invalid", "That file is not a DeutschFlow library export.");
  }
  if (typeof backup.version !== "number" || !Number.isInteger(backup.version) || backup.version <= 0) {
    throw new ImportError("invalid", "Invalid backup version.");
  }
  if (backup.version > BACKUP_VERSION) {
    throw new ImportError("newer", "That backup was made by a newer version of DeutschFlow.");
  }

  // Validate structural shape
  if (
    !Array.isArray(backup.vocabulary) ||
    !Array.isArray(backup.transcripts) ||
    !Array.isArray(backup.userStats) ||
    !Array.isArray(backup.activityLog)
  ) {
    throw new ImportError("invalid", "Malformed backup structure: collections must be arrays.");
  }

  // Resource limits
  if (backup.vocabulary.length > MAX_VOCABULARY_ROWS) {
    throw new ImportError("invalid", `Vocabulary count exceeds limit (${MAX_VOCABULARY_ROWS}).`);
  }
  if (backup.transcripts.length > MAX_TRANSCRIPT_ROWS) {
    throw new ImportError("invalid", `Transcripts count exceeds limit (${MAX_TRANSCRIPT_ROWS}).`);
  }
  if (backup.activityLog.length > MAX_ACTIVITY_ROWS) {
    throw new ImportError("invalid", `Activity log count exceeds limit (${MAX_ACTIVITY_ROWS}).`);
  }
  if (backup.userStats.length > MAX_USER_STATS_ROWS) {
    throw new ImportError("invalid", `User stats count exceeds limit (${MAX_USER_STATS_ROWS}).`);
  }

  // Pre-validate all records before touching storage
  for (const row of backup.vocabulary) {
    if (!isRecord(row)) {
      throw new ImportError("invalid", "Vocabulary entry must be an object.");
    }
    const germanText = str(row.germanText).trim();
    const englishTranslation = str(row.englishTranslation).trim();
    if (germanText.length > MAX_SHORT_FIELD_LENGTH || englishTranslation.length > MAX_FIELD_LENGTH) {
      throw new ImportError("invalid", "Vocabulary field exceeds maximum allowed length.");
    }
    if (
      str(row.exampleSentence).length > MAX_FIELD_LENGTH ||
      str(row.article).length > MAX_SHORT_FIELD_LENGTH ||
      str(row.plural).length > MAX_SHORT_FIELD_LENGTH ||
      str(row.conjugation).length > MAX_SHORT_FIELD_LENGTH ||
      str(row.synonyms).length > MAX_FIELD_LENGTH ||
      str(row.antonyms).length > MAX_FIELD_LENGTH
    ) {
      throw new ImportError("invalid", "Vocabulary metadata field exceeds maximum allowed length.");
    }
    if (row.timestamp !== undefined && (!Number.isSafeInteger(row.timestamp) || (row.timestamp as number) < 0 || (row.timestamp as number) > MAX_FUTURE_TIMESTAMP)) {
      throw new ImportError("invalid", "Invalid vocabulary timestamp.");
    }
    if (row.nextReview !== undefined && (!Number.isSafeInteger(row.nextReview) || (row.nextReview as number) < 0 || (row.nextReview as number) > MAX_FUTURE_TIMESTAMP)) {
      throw new ImportError("invalid", "Invalid vocabulary nextReview schedule.");
    }
    if (row.interval !== undefined && (!Number.isSafeInteger(row.interval) || (row.interval as number) < 0 || (row.interval as number) > 365)) {
      throw new ImportError("invalid", "Invalid vocabulary SRS interval (must be 0-365).");
    }
    if (row.easeFactor !== undefined && (typeof row.easeFactor !== "number" || !Number.isFinite(row.easeFactor) || (row.easeFactor as number) < 1.3 || (row.easeFactor as number) > 3.0)) {
      throw new ImportError("invalid", "Invalid vocabulary easeFactor (must be 1.3-3.0).");
    }
    if (row.reviewCount !== undefined && (!Number.isSafeInteger(row.reviewCount) || (row.reviewCount as number) < 0 || (row.reviewCount as number) > 100_000)) {
      throw new ImportError("invalid", "Invalid vocabulary reviewCount.");
    }
  }

  for (const row of backup.transcripts) {
    if (!isRecord(row)) {
      throw new ImportError("invalid", "Transcript entry must be an object.");
    }
    const fullText = str(row.fullText);
    if (fullText.length > MAX_TRANSCRIPT_LENGTH) {
      throw new ImportError("invalid", "Transcript text exceeds maximum allowed length.");
    }
    if (row.timestamp !== undefined && (!Number.isSafeInteger(row.timestamp) || (row.timestamp as number) < 0 || (row.timestamp as number) > MAX_FUTURE_TIMESTAMP)) {
      throw new ImportError("invalid", "Invalid transcript timestamp.");
    }
  }

  for (const row of backup.userStats) {
    if (!isRecord(row)) continue;
    if (row.xp !== undefined && (!Number.isSafeInteger(row.xp) || (row.xp as number) < 0 || (row.xp as number) > 10_000_000)) {
      throw new ImportError("invalid", "Invalid user stats XP.");
    }
    if (row.streak !== undefined && (!Number.isSafeInteger(row.streak) || (row.streak as number) < 0 || (row.streak as number) > 10_000)) {
      throw new ImportError("invalid", "Invalid user stats streak.");
    }
    if (row.lastActivityTimestamp !== undefined && (!Number.isSafeInteger(row.lastActivityTimestamp) || (row.lastActivityTimestamp as number) < 0 || (row.lastActivityTimestamp as number) > MAX_FUTURE_TIMESTAMP)) {
      throw new ImportError("invalid", "Invalid user stats timestamp.");
    }
  }

  for (const row of backup.activityLog) {
    if (!isRecord(row)) {
      throw new ImportError("invalid", "Activity log entry must be an object.");
    }
    const date = str(row.date);
    if (!isValidCalendarDate(date)) {
      throw new ImportError("invalid", `Invalid calendar date in activity log: "${date}".`);
    }
    if (row.xpGained !== undefined && (!Number.isSafeInteger(row.xpGained) || (row.xpGained as number) < 0 || (row.xpGained as number) > 100_000)) {
      throw new ImportError("invalid", "Invalid activity log xpGained.");
    }
    if (row.timestamp !== undefined && (!Number.isSafeInteger(row.timestamp) || (row.timestamp as number) < 0 || (row.timestamp as number) > MAX_FUTURE_TIMESTAMP)) {
      throw new ImportError("invalid", "Invalid activity log timestamp.");
    }
  }

  const result: ImportResult = {
    vocabularyAdded: 0,
    vocabularyMerged: 0,
    transcriptsAdded: 0,
  };

  try {
    await db.transaction(
      "rw",
      db.vocabulary, db.transcripts, db.userStats, db.activityLog,
      () => applyImport(db, backup as Record<string, unknown>, result)
    );
  } catch (err) {
    if (err instanceof ImportError) throw err;
    throw new ImportError("storage", err instanceof Error ? err.message : "Storage error during import.");
  }

  return result;
}

/** The body of [importLibrary], run inside its transaction. */
async function applyImport(
  db: DeutschFlowDB,
  backup: Record<string, unknown>,
  result: ImportResult
): Promise<void> {
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
    const known = new Set((await db.transcripts.toArray()).map((t) => t.remoteId));
    for (const row of transcripts) {
      if (!isRecord(row)) continue;
      const fullText = str(row.fullText);
      if (!fullText) continue;
      const remoteId = sanitizeRemoteId(row.remoteId);
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
    if (!isValidCalendarDate(date)) continue;
    const existing = await db.activityLog.get(date);
    await db.activityLog.put({
      date,
      xpGained: Math.max(existing?.xpGained ?? 0, num(row.xpGained, 0)),
      timestamp: num(row.timestamp, Date.now()),
    });
  }
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

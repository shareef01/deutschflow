import type { DeutschFlowDB, TranscriptEntry, UserStatsEntry, VocabularyEntry } from "./schema";
import { foldGermanKey } from "./schema";

/**
 * Repository — Room DAO transactions ported 1:1.
 *
 * Mirrors app/src/main/java/com/aus/deutschflow/data/local/dao/:
 *   VocabularyDao.save()      → saveVocabulary()  (one rw transaction)
 *   VocabularyDao.findByGermanText() → findByGermanText()
 *   UserStatsDao.getUserStatsOnce() / insertOrUpdate → rewardXp()
 *   TranscriptDao / VocabularyDao deletes → delete*()
 *
 * The two subtle behaviours are preserved exactly:
 * 1. saveVocabulary is ONE transaction — the read decides what the write does,
 *    and two concurrent saves of the same word must not both insert.
 * 2. rewardXp is a read-modify-write in one transaction, and the streak compares
 *    CALENDAR DAYS in the device's zone, not "24 hours apart".
 */

/**
 * A word can be met again and learned a bit more each time. All fields except
 * the two essentials are optional: a word typed in by hand arrives with only
 * text + translation; an interrogated word carries the full anatomy.
 */
export type VocabularyInput = {
  id?: number;
  germanText: string;
  englishTranslation: string;
  timestamp?: number;
  exampleSentence?: string;
  article?: string;
  plural?: string;
  conjugation?: string;
};

/**
 * The fold of the Android `VocabularyEntity.mergedWith`: a field the newcomer
 * fills in wins, a field it leaves blank keeps what was already known; `id` and
 * `germanText` stay as they are (identity is not up for negotiation, and
 * re-saving must not recapitalise the library); `timestamp` takes the later of
 * the two so a word touched again surfaces at the top of the list.
 */
function mergedWith(existing: VocabularyEntry, incoming: VocabularyInput): VocabularyEntry {
  return {
    ...existing,
    englishTranslation: incoming.englishTranslation || existing.englishTranslation,
    exampleSentence: incoming.exampleSentence || existing.exampleSentence,
    article: incoming.article || existing.article,
    plural: incoming.plural || existing.plural,
    conjugation: incoming.conjugation || existing.conjugation,
    timestamp: Math.max(existing.timestamp, incoming.timestamp ?? 0),
  };
}

/**
 * The one way a word enters or changes in the library — `VocabularyDao.save()`.
 *
 * NOCASE-unique on germanText, so a plain insert would throw the moment a word
 * was saved twice, or edited into a name another row already holds. The
 * collision is resolved by folding the rows together, never by crashing and
 * never by silently dropping one of them.
 */
export async function saveVocabulary(db: DeutschFlowDB, input: VocabularyInput): Promise<void> {
  const entry: VocabularyEntry = {
    id: input.id,
    germanText: input.germanText,
    // The key is derived here so no caller can persist a row whose key
    // disagrees with its text.
    germanTextKey: foldGermanKey(input.germanText),
    englishTranslation: input.englishTranslation,
    timestamp: input.timestamp ?? Date.now(),
    exampleSentence: input.exampleSentence ?? "",
    article: input.article ?? "",
    plural: input.plural ?? "",
    conjugation: input.conjugation ?? "",
  };

  await db.transaction("rw", db.vocabulary, async () => {
    const existing = await db.vocabulary.where("germanTextKey").equals(entry.germanTextKey).first();

    if (!existing) {
      // Nothing holds that word yet: a new entry, or a rename onto a free name.
      if (entry.id === undefined) {
        await db.vocabulary.add(entry);
      } else {
        await db.vocabulary.put(entry);
      }
      return;
    }

    if (existing.id === entry.id) {
      // An edit the user typed wins outright — a correction, not a second sighting.
      await db.vocabulary.put(entry);
      return;
    }

    // Another row owns the word. Fold into it and let the newcomer go — which
    // for a save is a row that never existed, and for a rename is the row being
    // renamed onto its new twin.
    if (entry.id !== undefined) {
      await db.vocabulary.delete(entry.id);
    }
    await db.vocabulary.put(mergedWith(existing, entry));
  });
}

/** NOCASE lookup: "hund" finds the row saved as "Hund". */
export async function findByGermanText(db: DeutschFlowDB, germanText: string) {
  return db.vocabulary.where("germanTextKey").equals(foldGermanKey(germanText)).first();
}

/** Live (Flow-like) list of the library, newest first. */
export function observeVocabulary(db: DeutschFlowDB) {
  return db.vocabulary.orderBy("timestamp").reverse().toArray();
}

/** Live list of transcripts, newest first — TranscriptDao.getAllTranscripts(). */
export function observeTranscripts(db: DeutschFlowDB) {
  return db.transcripts.orderBy("timestamp").reverse().toArray();
}

/** One-shot read of the whole library, newest first — the Study/Practice snapshots. */
export async function getAllVocabulary(db: DeutschFlowDB): Promise<VocabularyEntry[]> {
  return db.vocabulary.orderBy("timestamp").reverse().toArray();
}

export async function insertTranscript(
  db: DeutschFlowDB,
  fullText: string,
  timestamp?: number
): Promise<void> {
  await db.transcripts.add({ fullText, timestamp: timestamp ?? Date.now() });
}

export async function deleteTranscript(
  db: DeutschFlowDB,
  transcript: Pick<TranscriptEntry, "id">
): Promise<void> {
  if (transcript.id !== undefined) await db.transcripts.delete(transcript.id);
}

export async function deleteAllTranscripts(db: DeutschFlowDB): Promise<void> {
  await db.transcripts.clear();
}

export async function deleteVocabulary(db: DeutschFlowDB, entry: VocabularyEntry): Promise<void> {
  if (entry.id !== undefined) await db.vocabulary.delete(entry.id);
}

export async function deleteAllVocabulary(db: DeutschFlowDB): Promise<void> {
  await db.vocabulary.clear();
}

/* ---------------------------------------------------------------------------
   User stats — XP and streak
   --------------------------------------------------------------------------- */

export const XP_PER_CARD = 10;

export async function getUserStatsOnce(db: DeutschFlowDB): Promise<UserStatsEntry> {
  return (
    (await db.userStats.where("id").equals(1).first()) ?? {
      id: 1,
      xp: 0,
      streak: 0,
      lastActivityTimestamp: 0,
    }
  );
}

/** Live singleton for the Settings telemetry grid. */
export function observeUserStats(db: DeutschFlowDB) {
  return db.userStats.where("id").equals(1).first();
}

/**
 * Compares calendar days in the device's zone — StudyViewModel.nextStreak().
 *
 * The old rule treated any gap over 24h as "the next day" (a streak that could
 * never break), while two sessions 23 hours apart never counted as consecutive
 * days. Calendar-day comparison fixes both.
 */
export function nextStreak(currentStreak: number, lastActivity: number, now: number): number {
  if (lastActivity <= 0 || currentStreak <= 0) return 1;
  const days = daysBetween(lastActivity, now);
  if (days === 0) return currentStreak;
  if (days === 1) return currentStreak + 1;
  return 1;
}

function daysBetween(from: number, to: number): number {
  const a = new Date(from);
  const b = new Date(to);
  const startOfDayA = new Date(a.getFullYear(), a.getMonth(), a.getDate()).getTime();
  const startOfDayB = new Date(b.getFullYear(), b.getMonth(), b.getDate()).getTime();
  return Math.round((startOfDayB - startOfDayA) / 86_400_000);
}

/**
 * Banks XP and advances the streak — atomically, like the Room transaction.
 *
 * The read and the write are one unit: two rapid "Got it!" taps must not both
 * read the same row before either writes (which used to swallow an award and
 * write lastActivityTimestamp out of order). The once-per-session guard lives
 * in the Study store, mirroring StudyViewModel.awardedCardIds.
 */
export async function rewardXp(db: DeutschFlowDB, points: number = XP_PER_CARD): Promise<UserStatsEntry> {
  return db.transaction("rw", db.userStats, async () => {
    const stats = await getUserStatsOnce(db);
    const now = Date.now();
    const updated: UserStatsEntry = {
      ...stats,
      xp: stats.xp + points,
      streak: nextStreak(stats.streak, stats.lastActivityTimestamp, now),
      lastActivityTimestamp: now,
    };
    await db.userStats.put(updated);
    return updated;
  });
}

/* ---------------------------------------------------------------------------
   Progress wiping — Settings → "Clear all progress"
   --------------------------------------------------------------------------- */

/** Clears the three Room tables; settings (dialect, API key) are kept. */
export async function clearAllProgress(db: DeutschFlowDB): Promise<void> {
  await db.transaction("rw", db.vocabulary, db.transcripts, db.userStats, async () => {
    await db.vocabulary.clear();
    await db.transcripts.clear();
    await db.userStats.clear();
  });
}

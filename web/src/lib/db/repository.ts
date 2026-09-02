import type {
  DeutschFlowDB, TranscriptEntry, UserStatsEntry, VocabularyEntry, ActivityEntry,
  RoleplayMessageEntry,
} from "./schema";
import { foldGermanKey } from "./schema";
import { requestPersistentStorage } from "./backup";

/**
 * Repository — Room DAO transactions ported 1:1.
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
  synonyms?: string;
  antonyms?: string;
};

function mergedWith(existing: VocabularyEntry, incoming: VocabularyInput): VocabularyEntry {
  return {
    ...existing,
    englishTranslation: incoming.englishTranslation || existing.englishTranslation,
    exampleSentence: incoming.exampleSentence || existing.exampleSentence,
    article: incoming.article || existing.article,
    plural: incoming.plural || existing.plural,
    conjugation: incoming.conjugation || existing.conjugation,
    synonyms: incoming.synonyms || existing.synonyms,
    antonyms: incoming.antonyms || existing.antonyms,
    timestamp: Math.max(existing.timestamp, incoming.timestamp ?? 0),
    lastModifiedAt: Date.now()
  };
}

/** A word nobody has answered yet: due now, on SM-2's starting ease. */
export const NEW_CARD_SCHEDULE = {
  nextReview: 0,
  interval: 0,
  easeFactor: 2.5,
  reviewCount: 0,
} as const;

/**
 * Asked once per session, on the first save.
 *
 * Here rather than at load: a browser that prompts (Firefox) should do it at a
 * moment the user is doing something worth keeping, not while the app is booting.
 */
let persistenceRequested = false;

export async function saveVocabulary(db: DeutschFlowDB, input: VocabularyInput): Promise<void> {
  if (!persistenceRequested) {
    persistenceRequested = true;
    // Deliberately not awaited: whether the browser grants it changes nothing
    // about this write, and Safari never resolves it usefully.
    void requestPersistentStorage();
  }

  const now = Date.now();

  await db.transaction("rw", db.vocabulary, async () => {
    /**
     * The row this save is editing, if it is an edit at all.
     *
     * Its schedule and its cloud identity belong to the word, not to the text
     * the user just typed: building them fresh here reset a card reviewed
     * twenty times back to new, and minted a second remoteId for a record the
     * cloud already knew. Read inside the transaction, so the row cannot move
     * between this read and the write below.
     */
    const editing = input.id === undefined ? undefined : await db.vocabulary.get(input.id);

    const entry: VocabularyEntry = {
      id: input.id,
      germanText: input.germanText,
      germanTextKey: foldGermanKey(input.germanText),
      englishTranslation: input.englishTranslation,
      timestamp: input.timestamp ?? now,
      exampleSentence: input.exampleSentence ?? "",
      article: input.article ?? "",
      plural: input.plural ?? "",
      conjugation: input.conjugation ?? "",
      synonyms: input.synonyms ?? "",
      antonyms: input.antonyms ?? "",

      nextReview: editing?.nextReview ?? NEW_CARD_SCHEDULE.nextReview,
      interval: editing?.interval ?? NEW_CARD_SCHEDULE.interval,
      easeFactor: editing?.easeFactor ?? NEW_CARD_SCHEDULE.easeFactor,
      reviewCount: editing?.reviewCount ?? NEW_CARD_SCHEDULE.reviewCount,

      // `||`, not `??`: rows migrated from before the sync columns carry an
      // empty string, which needs a real id just as much as a missing one.
      remoteId: editing?.remoteId || crypto.randomUUID(),
      lastModifiedAt: now,
    };

    const existing = await db.vocabulary.where("germanTextKey").equals(entry.germanTextKey).first();

    if (!existing) {
      if (entry.id === undefined) {
        await db.vocabulary.add(entry);
      } else {
        await db.vocabulary.put(entry);
      }
      return;
    }

    // An edit the user typed wins outright — a correction, not a second
    // sighting. `entry` already carries this row's schedule and remoteId.
    if (existing.id === entry.id) {
      await db.vocabulary.put(entry);
      return;
    }

    if (entry.id !== undefined) {
      await db.vocabulary.delete(entry.id);
    }
    await db.vocabulary.put(mergedWith(existing, entry));
  });
}

export async function updateVocabulary(db: DeutschFlowDB, entry: VocabularyEntry): Promise<void> {
  await db.vocabulary.put({ ...entry, lastModifiedAt: Date.now() });
}

export async function findByGermanText(db: DeutschFlowDB, germanText: string) {
  return db.vocabulary.where("germanTextKey").equals(foldGermanKey(germanText)).first();
}

export function observeVocabulary(db: DeutschFlowDB) {
  return db.vocabulary.orderBy("timestamp").reverse().toArray();
}

export function observeTranscripts(db: DeutschFlowDB) {
  return db.transcripts.orderBy("timestamp").reverse().toArray();
}

export async function getAllVocabulary(db: DeutschFlowDB): Promise<VocabularyEntry[]> {
  return db.vocabulary.orderBy("timestamp").reverse().toArray();
}

/**
 * The cards ready for review — VocabularyDao.getDueVocabulary().
 *
 * Same order as the Room query: scheduled reviews first, in the order they fell
 * due, then words never answered, newest first. Sorting on `timestamp` alone
 * ignored the schedule entirely, so the two apps walked one library differently.
 */
export async function getDueVocabulary(db: DeutschFlowDB, currentTime: number): Promise<VocabularyEntry[]> {
  const due = await db.vocabulary.where("nextReview").belowOrEqual(currentTime).toArray();
  return due.sort(
    (a, b) =>
      Number(a.nextReview === 0) - Number(b.nextReview === 0) ||
      a.nextReview - b.nextReview ||
      b.timestamp - a.timestamp
  );
}

export async function insertTranscript(
  db: DeutschFlowDB,
  fullText: string,
  timestamp?: number
): Promise<void> {
  const now = Date.now();
  await db.transcripts.add({
    fullText,
    timestamp: timestamp ?? now,
    remoteId: crypto.randomUUID(),
    lastModifiedAt: now
  });
}

export async function deleteTranscript(
  db: DeutschFlowDB,
  transcript: Pick<TranscriptEntry, "id">
): Promise<void> {
  if (transcript.id !== undefined) await db.transcripts.delete(transcript.id);
}

export async function deleteVocabulary(db: DeutschFlowDB, entry: VocabularyEntry): Promise<void> {
  if (entry.id !== undefined) await db.vocabulary.delete(entry.id);
}

/* ---------------------------------------------------------------------------
   User stats — XP and streak
   --------------------------------------------------------------------------- */

export const XP_PER_CARD = 10;

/**
 * The daily goal ring's target, written once: the dashboard drew its own literal
 * 50 with nothing tying it to what a reviewed card pays out.
 */
export const DAILY_XP_GOAL = XP_PER_CARD * 5;

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

export function observeUserStats(db: DeutschFlowDB) {
  return db.userStats.where("id").equals(1).first();
}

/**
 * The heatmap's window, newest first — bounded, like the Room query, and exactly
 * the dashboard's 12 weeks x 7 = 84 cells: 92 left eight queried days undrawable.
 */
export const HEATMAP_DAYS = 84;

export async function observeActivityLog(db: DeutschFlowDB) {
    const since = todayKey(new Date(Date.now() - HEATMAP_DAYS * 86_400_000));
    // Sorted after the read, not by reversing the collection: sortBy re-sorts in
    // JS, so a reverse() before it is simply discarded.
    const days = await db.activityLog.where("date").aboveOrEqual(since).toArray();
    return days.sort((a, b) => b.date.localeCompare(a.date));
}

/**
 * Today as "YYYY-MM-DD" in the device's zone — the activity_log primary key.
 *
 * Local, not `toISOString()`, for the same reason `daysBetween` below compares
 * calendar days: a UTC key rolls the heatmap and the daily goal over at the
 * wrong hour for every user outside UTC, and disagrees with the streak sitting
 * next to it. Android writes the same key via `LocalDate.now()`.
 *
 * Exported so the dashboard reads under exactly the key this writes.
 */
export function todayKey(now: Date = new Date()): string {
  const month = `${now.getMonth() + 1}`.padStart(2, "0");
  const day = `${now.getDate()}`.padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

export async function addActivityXp(db: DeutschFlowDB, amount: number): Promise<void> {
    const date = todayKey();
    await db.transaction("rw", db.activityLog, async () => {
        const current = await db.activityLog.get(date);
        if (current) {
            await db.activityLog.put({ ...current, xpGained: current.xpGained + amount });
        } else {
            await db.activityLog.add({ date, xpGained: amount, timestamp: Date.now() });
        }
    });
}

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

export async function rewardXp(db: DeutschFlowDB, points: number = XP_PER_CARD): Promise<UserStatsEntry> {
  return db.transaction("rw", db.userStats, db.activityLog, async () => {
    const stats = await getUserStatsOnce(db);
    const now = Date.now();
    const updated: UserStatsEntry = {
      ...stats,
      xp: stats.xp + points,
      streak: nextStreak(stats.streak, stats.lastActivityTimestamp, now),
      lastActivityTimestamp: now,
    };
    await db.userStats.put(updated);

    // Also update activity log
    await addActivityXp(db, points);

    return updated;
  });
}

export async function clearAllProgress(db: DeutschFlowDB): Promise<void> {
  await db.transaction(
    "rw",
    db.vocabulary, db.transcripts, db.userStats, db.activityLog, db.roleplayMessages,
    async () => {
      await db.vocabulary.clear();
      await db.transcripts.clear();
      await db.userStats.clear();
      await db.activityLog.clear();
      // The saved roleplay is the user's speech too. "Clear all progress" that
      // left a conversation behind would be the one thing it promised not to do.
      await db.roleplayMessages.clear();
    }
  );
}

// --- roleplay conversation --------------------------------------------------
//
// Mirrors RoleplayDao. Only the current conversation is stored; starting a new
// scenario clears it. Every one of these swallows its own failure: a chat that
// cannot be saved is a chat the user still has in front of them, and throwing
// out of a turn to report it would cost them the turn as well as the copy.

/** The saved conversation, oldest turn first — the order the chat renders in. */
export async function loadConversation(db: DeutschFlowDB): Promise<RoleplayMessageEntry[]> {
  try {
    return await db.roleplayMessages.orderBy("position").toArray();
  } catch {
    return [];
  }
}

/** Writes one turn. `put`, so a resent turn replaces the row at its position. */
export async function saveConversationTurn(
  db: DeutschFlowDB,
  message: RoleplayMessageEntry
): Promise<void> {
  try {
    await db.roleplayMessages.put(message);
  } catch {
    // Storage is best-effort here; see the note above.
  }
}

export async function clearConversation(db: DeutschFlowDB): Promise<void> {
  try {
    await db.roleplayMessages.clear();
  } catch {
    // As above.
  }
}

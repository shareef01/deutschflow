import type {
  DeutschFlowDB, TranscriptEntry, UserStatsEntry, VocabularyEntry,
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
  lastModifiedAt?: number;
  nextReview?: number;
  interval?: number;
  easeFactor?: number;
  reviewCount?: number;
  remoteId?: string;
  /**
   * When the card was last reviewed. Only `recordReview` produces it; a save carries
   * it so an edit cannot delete it (see [saveVocabulary]) and so a row that has gone
   * missing under an edit keeps its study timestamp rather than losing it.
   */
  lastStudiedAt?: number;
};

export function isUnreviewedSchedule(
  entry: Pick<VocabularyEntry, "reviewCount" | "interval" | "easeFactor" | "nextReview">
): boolean {
  return (
    entry.reviewCount === 0 &&
    entry.interval === 0 &&
    entry.nextReview === 0 &&
    (entry.easeFactor === 2.5 || entry.easeFactor === undefined)
  );
}

/**
 * Pedagogical Spaced-Repetition Schedule Merge Policy:
 *
 * 1. UNREVIEWED vs STUDIED:
 *    A card with no study history (reviewCount === 0, interval === 0, nextReview === 0, easeFactor === 2.5)
 *    never overwrites an actively studied card. If one card is unstudied and the other has study history,
 *    the studied card's schedule is chosen unconditionally.
 *
 * 2. RECENCY OF STUDY (Anti-Lapse-Erasure Principle):
 *    When both cards have been studied, the schedule from the more recent interaction
 *    (higher lastModifiedAt) is authoritative. Crucially, if a learner recently failed a card
 *    (quality AGAIN, resetting interval = 0, reviewCount = 0, nextReview = 0/now), an older copy
 *    with a large interval (e.g. interval = 30 days) MUST NOT wipe out the lapse.
 *    Preferring a longer interval over a recent lapse breaks spaced repetition by hiding
 *    forgotten words for weeks or months.
 *
 * 3. SAFETY IN FAVOR OF RECALL (Tied Timestamps):
 *    If modification timestamps are tied:
 *    - If one card is due/lapsed (nextReview <= now || interval === 0) and the other is scheduled
 *      in the future (nextReview > now && interval > 0), the DUE/LAPSED card wins!
 *      Reviewing a card earlier than necessary does no pedagogical harm, whereas postponing
 *      a forgotten card causes severe retention loss.
 *    - If both cards are scheduled in the future, the card with the SOONER due date (min(nextReview))
 *      is preferred to protect against memory decay.
 *    - Tie-breaker: higher reviewCount, then longer interval, then keep survivor.
 */
export function withFurthestScheduleOf(
  survivor: VocabularyEntry,
  other: Pick<VocabularyEntry, "reviewCount" | "interval" | "easeFactor" | "nextReview"> &
    Partial<Pick<VocabularyEntry, "lastModifiedAt" | "lastStudiedAt">>,
  now: number = Date.now(),
  options?: {
    survivorLastStudiedAt?: number;
    otherLastStudiedAt?: number;
  }
): VocabularyEntry {
  const survivorUnreviewed = isUnreviewedSchedule(survivor);
  const otherUnreviewed = isUnreviewedSchedule(other);

  // 1. One is fresh unreviewed, the other is studied
  if (survivorUnreviewed && !otherUnreviewed) {
    return {
      ...survivor,
      nextReview: other.nextReview,
      interval: other.interval,
      easeFactor: other.easeFactor,
      reviewCount: other.reviewCount,
      lastStudiedAt: other.lastStudiedAt ?? options?.otherLastStudiedAt ?? survivor.lastStudiedAt,
    };
  }
  if (!survivorUnreviewed && otherUnreviewed) {
    return survivor;
  }
  if (survivorUnreviewed && otherUnreviewed) {
    return survivor;
  }

  // 2. Both have study history: compare dedicated study timestamps (actual review event time or lastStudiedAt)
  // Decoupled from lastModifiedAt, which changes during typographical, translation, or content edits.
  const survivorStudied = options?.survivorLastStudiedAt ?? survivor.lastStudiedAt ?? 0;
  const otherStudied = options?.otherLastStudiedAt ?? other.lastStudiedAt ?? 0;

  if (otherStudied > 0 || survivorStudied > 0) {
    if (otherStudied > survivorStudied) {
      // Other was reviewed strictly after survivor
      return {
        ...survivor,
        nextReview: other.nextReview,
        interval: other.interval,
        easeFactor: other.easeFactor,
        reviewCount: other.reviewCount,
        lastStudiedAt: otherStudied,
      };
    }
    if (survivorStudied > otherStudied) {
      // Survivor was reviewed strictly after other
      return survivor;
    }
  }

  // 3. Timestamps are identical or unavailable: Apply Safety-in-Favor-of-Recall
  // A due or lapsed card takes priority over a future schedule to protect against memory decay.
  const survivorIsDueOrLapsed = survivor.nextReview <= now || survivor.interval === 0;
  const otherIsDueOrLapsed = other.nextReview <= now || other.interval === 0;

  if (otherIsDueOrLapsed && !survivorIsDueOrLapsed) {
    // Other requires review now; do not postpone into the future
    return {
      ...survivor,
      nextReview: other.nextReview,
      interval: other.interval,
      easeFactor: other.easeFactor,
      reviewCount: other.reviewCount,
      lastStudiedAt: otherStudied || survivorStudied || undefined,
    };
  }
  if (survivorIsDueOrLapsed && !otherIsDueOrLapsed) {
    // Survivor requires review now; keep it due
    return survivor;
  }

  // If both are scheduled in the future, choose the sooner due date
  if (!survivorIsDueOrLapsed && !otherIsDueOrLapsed) {
    if (other.nextReview < survivor.nextReview) {
      return {
        ...survivor,
        nextReview: other.nextReview,
        interval: other.interval,
        easeFactor: other.easeFactor,
        reviewCount: other.reviewCount,
        lastStudiedAt: otherStudied || survivorStudied || undefined,
      };
    }
    if (survivor.nextReview < other.nextReview) {
      return survivor;
    }
  }

  // Final deterministic tie-breaker: most successful reviews, then longest interval
  const otherIsFurther =
    other.reviewCount > survivor.reviewCount ||
    (other.reviewCount === survivor.reviewCount && other.interval > survivor.interval);

  if (!otherIsFurther) return survivor;
  return {
    ...survivor,
    nextReview: other.nextReview,
    interval: other.interval,
    easeFactor: other.easeFactor,
    reviewCount: other.reviewCount,
    lastStudiedAt: otherStudied || survivorStudied || undefined,
  };
}

export function mergedWith(existing: VocabularyEntry, incoming: VocabularyInput, now = Date.now()): VocabularyEntry {
  return {
    ...existing,
    englishTranslation: incoming.englishTranslation || existing.englishTranslation,
    exampleSentence: incoming.exampleSentence || existing.exampleSentence,
    article: incoming.article && incoming.article !== "none" ? incoming.article : existing.article,
    plural: incoming.plural || existing.plural,
    conjugation: incoming.conjugation || existing.conjugation,
    synonyms: incoming.synonyms || existing.synonyms,
    antonyms: incoming.antonyms || existing.antonyms,
    timestamp: Math.max(existing.timestamp, incoming.timestamp ?? 0),
    lastModifiedAt: Math.max(existing.lastModifiedAt || 0, incoming.lastModifiedAt || 0, now)
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

export function ensurePersistentStorageRequested(): void {
  if (!persistenceRequested) {
    persistenceRequested = true;
    void requestPersistentStorage();
  }
}

export async function saveVocabulary(db: DeutschFlowDB, input: VocabularyInput): Promise<void> {
  ensurePersistentStorageRequested();

  const now = Date.now();

  await db.transaction("rw", [db.vocabulary, db.reviewEvents], async () => {
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
      timestamp: input.timestamp ?? editing?.timestamp ?? now,
      exampleSentence: input.exampleSentence ?? editing?.exampleSentence ?? "",
      article: input.article ?? editing?.article ?? "",
      plural: input.plural ?? editing?.plural ?? "",
      conjugation: input.conjugation ?? editing?.conjugation ?? "",
      synonyms: input.synonyms ?? editing?.synonyms ?? "",
      antonyms: input.antonyms ?? editing?.antonyms ?? "",

      nextReview: input.nextReview ?? editing?.nextReview ?? NEW_CARD_SCHEDULE.nextReview,
      interval: input.interval ?? editing?.interval ?? NEW_CARD_SCHEDULE.interval,
      easeFactor: input.easeFactor ?? editing?.easeFactor ?? NEW_CARD_SCHEDULE.easeFactor,
      reviewCount: input.reviewCount ?? editing?.reviewCount ?? NEW_CARD_SCHEDULE.reviewCount,

      // `||`, not `??`: rows migrated from before the sync columns carry an
      // empty string, which needs a real id just as much as a missing one.
      remoteId: input.remoteId || editing?.remoteId || crypto.randomUUID(),
      lastModifiedAt: Math.max(editing?.lastModifiedAt ?? 0, input.lastModifiedAt ?? 0, now),

      // The row is written back whole below, so a field left out of this literal
      // is a field the edit deletes. `lastStudiedAt` is set only by recordReview,
      // never by the edit sheet, so it comes from the row being edited: leaving it
      // out made renaming a card also erase when it was last studied, which is what
      // the merge rules in withFurthestScheduleOf() compare. The caller's value is
      // the fallback for an edit whose row is no longer there - the schedule it
      // passes survives that case, and its study timestamp should travel with it.
      lastStudiedAt: editing?.lastStudiedAt ?? input.lastStudiedAt,
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

    // Another row owns the word. Fold into it and let the newcomer go - which
    // for a save is a row that never existed, and for a rename is the row being
    // renamed onto its existing twin.
    //
    // A rename takes its learning with it: the survivor keeps whichever of the
    // two schedules is further along, and the departing row's review history
    // is moved onto the survivor before the row is deleted. Keeping the
    // survivor's schedule unconditionally threw away months of progress
    // whenever the renamed card was the one being studied.
    if (entry.id !== undefined) {
      const departingEvents = await db.reviewEvents.where("vocabularyId").equals(entry.id).toArray();
      const existingEvents = await db.reviewEvents.where("vocabularyId").equals(existing.id!).toArray();
      const existingRemoteIds = new Set(existingEvents.map((e) => e.remoteId.toLowerCase()));

      for (const event of departingEvents) {
        if (existingRemoteIds.has(event.remoteId.toLowerCase())) {
          if (event.id !== undefined) await db.reviewEvents.delete(event.id);
        } else {
          if (event.id !== undefined) {
            await db.reviewEvents.update(event.id, { vocabularyId: existing.id! });
            existingRemoteIds.add(event.remoteId.toLowerCase());
          }
        }
      }

      await db.vocabulary.delete(entry.id);

      const departingMaxReview = departingEvents.reduce((max, e) => Math.max(max, e.reviewedAtTimestamp || 0), 0);
      const existingMaxReview = existingEvents.reduce((max, e) => Math.max(max, e.reviewedAtTimestamp || 0), 0);
      const otherLastStudiedAt = Math.max(entry.lastStudiedAt || 0, departingMaxReview);
      const survivorLastStudiedAt = Math.max(existing.lastStudiedAt || 0, existingMaxReview);

      const mergedContent = mergedWith(existing, entry, now);
      const finalEntry = withFurthestScheduleOf(mergedContent, entry, now, {
        survivorLastStudiedAt,
        otherLastStudiedAt,
      });
      finalEntry.remoteId = existing.remoteId || entry.remoteId || crypto.randomUUID();
      finalEntry.lastStudiedAt = finalEntry.lastStudiedAt || Math.max(survivorLastStudiedAt, otherLastStudiedAt) || undefined;

      await db.vocabulary.put(finalEntry);
      return;
    }

    const existingEvents = await db.reviewEvents.where("vocabularyId").equals(existing.id!).toArray();
    const existingMaxReview = existingEvents.reduce((max, e) => Math.max(max, e.reviewedAtTimestamp || 0), 0);
    const survivorLastStudiedAt = Math.max(existing.lastStudiedAt || 0, existingMaxReview);
    const otherLastStudiedAt = entry.lastStudiedAt || 0;

    const mergedContent = mergedWith(existing, entry, now);
    const finalEntry = withFurthestScheduleOf(mergedContent, entry, now, {
      survivorLastStudiedAt,
      otherLastStudiedAt,
    });
    finalEntry.remoteId = existing.remoteId || entry.remoteId || crypto.randomUUID();
    finalEntry.lastStudiedAt = finalEntry.lastStudiedAt || Math.max(survivorLastStudiedAt, otherLastStudiedAt) || undefined;

    await db.vocabulary.put(finalEntry);
  });
}

/**
 * Puts a deleted word back, for the library's Undo.
 * Under its original id when that is possible, which keeps its review history attached.
 * If the word was saved again in the meantime, the two are folded together.
 */
export async function restoreVocabulary(db: DeutschFlowDB, entry: VocabularyEntry): Promise<void> {
  const now = Date.now();
  const key = foldGermanKey(entry.germanText);

  await db.transaction("rw", [db.vocabulary, db.reviewEvents, db.settings], async () => {
    const occupant = await db.vocabulary.where("germanTextKey").equals(key).first();
    const historySetting = entry.remoteId
      ? await db.settings.get(`deletion_history:${entry.remoteId.toLowerCase()}`)
      : undefined;
    let savedEventRemoteIds: Set<string> | undefined;
    if (historySetting?.value) {
      try {
        const parsed = JSON.parse(historySetting.value);
        if (Array.isArray(parsed.eventRemoteIds)) {
          savedEventRemoteIds = new Set(parsed.eventRemoteIds.map((id: string) => String(id).toLowerCase()));
        }
      } catch {
        // ignore malformed history
      }
    }

    if (!occupant) {
      if (entry.id !== undefined) {
        const idOccupant = await db.vocabulary.get(entry.id);
        if (!idOccupant) {
          await db.vocabulary.put({
            ...entry,
            germanTextKey: key,
            lastModifiedAt: Math.max(entry.lastModifiedAt || 0, now),
          });
          if (entry.remoteId) {
            await db.settings.delete(`deletion_history:${entry.remoteId.toLowerCase()}`);
          }
          return;
        }
      }
      const { id: _, ...withoutId } = entry;
      const newId = (await db.vocabulary.add({
        ...withoutId,
        germanTextKey: key,
        lastModifiedAt: Math.max(entry.lastModifiedAt || 0, now),
      })) as number;

      if (entry.id !== undefined) {
        if (savedEventRemoteIds && savedEventRemoteIds.size > 0) {
          const events = await db.reviewEvents.where("vocabularyId").equals(entry.id).toArray();
          for (const ev of events) {
            if (ev.id !== undefined && savedEventRemoteIds.has(ev.remoteId.toLowerCase())) {
              await db.reviewEvents.update(ev.id, { vocabularyId: newId });
            }
          }
        } else {
          const idOccupant = await db.vocabulary.get(entry.id);
          if (!idOccupant || idOccupant.remoteId.toLowerCase() === entry.remoteId.toLowerCase()) {
            await db.reviewEvents.where("vocabularyId").equals(entry.id).modify({ vocabularyId: newId });
          }
        }
      }

      if (entry.remoteId) {
        await db.settings.delete(`deletion_history:${entry.remoteId.toLowerCase()}`);
      }
    } else {
      const occupantEvents = occupant.id !== undefined ? await db.reviewEvents.where("vocabularyId").equals(occupant.id).toArray() : [];
      const entryEvents = entry.id !== undefined ? await db.reviewEvents.where("vocabularyId").equals(entry.id).toArray() : [];

      const survivorLastStudiedAt = Math.max(
        occupant.lastStudiedAt || 0,
        occupantEvents.reduce((max, e) => Math.max(max, e.reviewedAtTimestamp || 0), 0)
      );
      const otherLastStudiedAt = Math.max(
        entry.lastStudiedAt || 0,
        entryEvents.reduce((max, e) => Math.max(max, e.reviewedAtTimestamp || 0), 0)
      );

      const merged = withFurthestScheduleOf(mergedWith(occupant, entry, now), entry, now, {
        survivorLastStudiedAt,
        otherLastStudiedAt,
      });
      merged.lastStudiedAt = merged.lastStudiedAt || Math.max(survivorLastStudiedAt, otherLastStudiedAt) || undefined;
      await db.vocabulary.put(merged);

      if (entry.id !== undefined && occupant.id !== undefined && entry.id !== occupant.id) {
        const idOccupant = await db.vocabulary.get(entry.id);
        const isIdReused = idOccupant && idOccupant.remoteId.toLowerCase() !== entry.remoteId.toLowerCase();

        const departingEvents = entryEvents;
        const existingRemoteIds = new Set(occupantEvents.map((e) => e.remoteId.toLowerCase()));
        for (const event of departingEvents) {
          if (isIdReused && (!savedEventRemoteIds || !savedEventRemoteIds.has(event.remoteId.toLowerCase()))) {
            continue; // Skip review events belonging to the new occupant
          }
          if (existingRemoteIds.has(event.remoteId.toLowerCase())) {
            if (event.id !== undefined) await db.reviewEvents.delete(event.id);
          } else {
            if (event.id !== undefined) {
              await db.reviewEvents.update(event.id, { vocabularyId: occupant.id });
              existingRemoteIds.add(event.remoteId.toLowerCase());
            }
          }
        }
      }

      if (entry.remoteId) {
        await db.settings.delete(`deletion_history:${entry.remoteId.toLowerCase()}`);
      }
    }
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
): Promise<number> {
  ensurePersistentStorageRequested();
  const now = Date.now();
  const id = await db.transcripts.add({
    fullText,
    timestamp: timestamp ?? now,
    remoteId: crypto.randomUUID(),
    lastModifiedAt: now,
  });
  return id as number;
}

export async function updateTranscriptAnalysis(
  db: DeutschFlowDB,
  id: number,
  translation: string,
  analysisJson: string
): Promise<void> {
  await db.transcripts.update(id, {
    translation,
    analysisJson,
    lastModifiedAt: Date.now(),
  });
}

export async function deleteTranscript(
  db: DeutschFlowDB,
  transcript: Pick<TranscriptEntry, "id">
): Promise<void> {
  if (transcript.id !== undefined) await db.transcripts.delete(transcript.id);
}

/** Undo keeps the original identity and analysis and never replaces a newer copy. */
export async function restoreTranscript(db: DeutschFlowDB, transcript: TranscriptEntry): Promise<void> {
  await db.transaction("rw", db.transcripts, async () => {
    const existing = await db.transcripts.filter((row) => row.remoteId === transcript.remoteId).first();
    if (!existing) await db.transcripts.add({ ...transcript, id: undefined });
  });
}

export async function deleteVocabulary(db: DeutschFlowDB, entry: VocabularyEntry): Promise<void> {
  if (entry.id !== undefined) {
    await db.transaction("rw", [db.vocabulary, db.reviewEvents, db.settings], async () => {
      const events = await db.reviewEvents.where("vocabularyId").equals(entry.id!).toArray();
      const eventRemoteIds = events.map((e) => e.remoteId.toLowerCase());
      if (entry.remoteId) {
        await db.settings.put({
          key: `deletion_history:${entry.remoteId.toLowerCase()}`,
          value: JSON.stringify({
            originalId: entry.id,
            remoteId: entry.remoteId,
            eventRemoteIds,
            timestamp: Date.now(),
          }),
        });
      }
      await db.vocabulary.delete(entry.id!);
    });
  }
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

/**
 * Returns the calendar date HEATMAP_DAYS prior to referenceDate.
 * Decrements local calendar date rather than subtracting 84 * 86_400_000 ms,
 * so DST transitions (23h or 25h days) never skew the 84-day window.
 */
export function getHeatmapCutoffDate(referenceDate: Date = new Date()): Date {
  const cutoff = new Date(referenceDate.getFullYear(), referenceDate.getMonth(), referenceDate.getDate());
  cutoff.setDate(cutoff.getDate() - HEATMAP_DAYS);
  return cutoff;
}

export async function observeActivityLog(db: DeutschFlowDB, now: Date = new Date()) {
    const since = todayKey(getHeatmapCutoffDate(now));
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

/**
 * The streak to show right now — UserStatsEntity.currentStreak() on Android.
 *
 * The stored value only changes when a card is rated, so it kept showing a streak
 * long after it had lapsed. Studying yesterday still counts, since today can extend
 * it; a whole missed calendar day ends it.
 */
export function currentStreak(
  stats: Pick<UserStatsEntry, "streak" | "lastActivityTimestamp"> | null | undefined,
  now: number = Date.now()
): number {
  if (!stats || stats.streak <= 0 || stats.lastActivityTimestamp <= 0) return 0;
  return daysBetween(stats.lastActivityTimestamp, now) <= 1 ? stats.streak : 0;
}

/**
 * Local civil date ordinal: converts local year/month/date to integer UTC day number.
 * Ensures differences represent civil calendar days regardless of 23h / 25h DST changes.
 */
export function toCivilDateOrdinal(d: Date): number {
  return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86_400_000);
}

export function daysBetween(from: number, to: number): number {
  const a = new Date(from);
  const b = new Date(to);
  return toCivilDateOrdinal(b) - toCivilDateOrdinal(a);
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
    [db.vocabulary, db.transcripts, db.userStats, db.activityLog, db.roleplayMessages, db.reviewEvents],
    async () => {
      await db.vocabulary.clear();
      await db.transcripts.clear();
      await db.userStats.clear();
      await db.activityLog.clear();
      // The saved roleplay is the user's speech too. "Clear all progress" that
      // left a conversation behind would be the one thing it promised not to do.
      await db.roleplayMessages.clear();
      await db.reviewEvents.clear();
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

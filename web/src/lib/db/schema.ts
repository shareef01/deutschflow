import Dexie, { type Table } from "dexie";

/**
 * DeutschFlow database — the Dexie mirror of the Room schema (Room is at v12;
 * the two version numbers are independent and need not match).
 *
 * Mirrors app/src/main/java/com/aus/deutschflow/data/local/:
 *   entities/TranscriptEntity.kt    → transcripts
 *   entities/VocabularyEntity.kt    → vocabulary
 *   entities/UserStatsEntity.kt     → userStats
 *   entities/ActivityEntity.kt      → activityLog
 *   PreferenceManager (DataStore)   → settings (key-value rows)
 *
 * Index notes (ported from the Room @Index annotations):
 * - `timestamp` is indexed on transcripts and vocabulary — every screen orders
 *   by `timestamp DESC`.
 * - `nextReview` is indexed for the SRS due-query.
 * - `germanText` uniqueness is SQLite NOCASE. Dexie indexes are case-sensitive,
 *   so the unique index lives on `germanTextKey`, an ASCII-only fold of the
 *   word — the exact behaviour of SQLite's NOCASE, which folds ASCII and leaves
 *   umlauts alone ("Hund" ≡ "hund", but "Äpfel" ≢ "äpfel").
 * - `user_stats` is a singleton row with `id = 1`.
 * - `activityLog` is keyed by a local "YYYY-MM-DD" date — see `todayKey`.
 *
 * A version that adds an indexed field must also seed it in `.upgrade()`:
 * IndexedDB omits records whose key path is `undefined` from the index, so an
 * unseeded field silently hides every existing row from the query that uses it.
 */

export interface VocabularyEntry {
  id?: number;
  germanText: string;
  germanTextKey: string;
  englishTranslation: string;
  timestamp: number;
  exampleSentence: string;
  article: string;
  plural: string;
  conjugation: string;
  nextReview: number;
  interval: number;
  easeFactor: number;
  reviewCount: number;
  synonyms: string;
  antonyms: string;

  // Cloud Sync fields (Phase VIII)
  remoteId: string;
  lastModifiedAt: number;
}

export interface TranscriptEntry {
  id?: number;
  fullText: string;
  timestamp: number;

  // Cloud Sync fields (Phase VIII)
  remoteId: string;
  lastModifiedAt: number;
}

export interface UserStatsEntry {
  id: number;
  xp: number;
  streak: number;
  lastActivityTimestamp: number;
}

export interface ActivityEntry {
    date: string; // YYYY-MM-DD
    xpGained: number;
    timestamp: number;
}

export interface SettingEntry {
  key: string;
  value: string;
}

/**
 * ASCII-only lowercase fold, matching SQLite's NOCASE collation.
 *
 * German capitalises its nouns; the words arrive either from the model (spelled
 * correctly) or from the user typing the word they mean — so folding ASCII only,
 * exactly like the Android app, costs nothing and keeps "Äpfel" distinct from
 * "äpfel" as the original does.
 */
export function foldGermanKey(text: string): string {
  return text.replace(/[A-Z]/g, (c) => c.toLowerCase());
}

export class DeutschFlowDB extends Dexie {
  vocabulary!: Table<VocabularyEntry, number>;
  transcripts!: Table<TranscriptEntry, number>;
  userStats!: Table<UserStatsEntry, number>;
  activityLog!: Table<ActivityEntry, string>;
  settings!: Table<SettingEntry, string>;

  constructor(name: string = "deutschflow") {
    super(name);

    // Version 4: Added activityLog and Cloud Sync fields.
    this.version(4).stores({
      vocabulary: "++id, timestamp, &germanTextKey, nextReview",
      transcripts: "++id, timestamp",
      userStats: "id",
      activityLog: "date",
      settings: "key",
    }).upgrade(async (tx) => {
        const now = Date.now();
        // Both awaited: returning only the second left the first unreported, so
        // a failure to seed the vocabulary would not have failed the upgrade.
        await tx.table("vocabulary").toCollection().modify(v => {
            if (!v.remoteId) v.remoteId = crypto.randomUUID();
            if (!v.lastModifiedAt) v.lastModifiedAt = now;
        });
        await tx.table("transcripts").toCollection().modify(t => {
            if (!t.remoteId) t.remoteId = crypto.randomUUID();
            if (!t.lastModifiedAt) t.lastModifiedAt = now;
        });
    });

    this.version(3).stores({
      vocabulary: "++id, timestamp, &germanTextKey, nextReview",
      transcripts: "++id, timestamp",
      userStats: "id",
      settings: "key",
    });

    /**
     * Version 2: the SRS columns, and the `nextReview` index Study queries.
     *
     * The `.upgrade()` is not optional here, the way it would be on Android.
     * `ALTER TABLE ... DEFAULT 0` backfills every existing SQLite row; IndexedDB
     * gives no such guarantee — a record whose key path evaluates to `undefined`
     * is left out of the index entirely. Without this seeding, every word saved
     * before this version would vanish from `getDueVocabulary`, and Study would
     * open empty for everyone who already had a library.
     */
    this.version(2)
      .stores({
        vocabulary: "++id, timestamp, &germanTextKey, nextReview",
        transcripts: "++id, timestamp",
        userStats: "id",
        settings: "key",
      })
      .upgrade((tx) =>
        tx
          .table("vocabulary")
          .toCollection()
          .modify((v) => {
            // Due immediately, on SM-2's starting ease — the same state a word
            // saved today gets, so nothing jumps the queue and nothing is lost.
            if (v.nextReview === undefined) v.nextReview = 0;
            if (v.interval === undefined) v.interval = 0;
            if (v.easeFactor === undefined) v.easeFactor = 2.5;
            if (v.reviewCount === undefined) v.reviewCount = 0;
            if (v.synonyms === undefined) v.synonyms = "";
            if (v.antonyms === undefined) v.antonyms = "";
          })
      );

    this.version(1).stores({
      vocabulary: "++id, timestamp, &germanTextKey",
      transcripts: "++id, timestamp",
      userStats: "id",
      settings: "key",
    });
  }
}

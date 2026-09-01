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
 * - `germanText` uniqueness lives on `germanTextKey`, a full German fold of the
 *   word — see [foldGermanKey]. Android enforces the same key on the same column.
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
 * Folds a German word to the form all its spellings share.
 *
 * This used to be an ASCII-only fold, chosen to match SQLite's NOCASE collation —
 * and it matched it, including the part that was wrong. NOCASE folds A–Z and
 * nothing else, so "Hund" and "hund" were one word while "Übung" and "übung" were
 * two, as were "Öl"/"öl" and "Ärger"/"ärger". Every umlaut-initial German noun
 * escaped deduplication and quietly accumulated copies. Both platforms were
 * consistently wrong; both are now consistently right.
 *
 * Full case fold, then the standard transliteration for keyboards without umlauts,
 * which also makes "Straße" and "Strasse" one word — the correct German
 * equivalence, and the same fold `lib/scoring.ts` has always used to judge
 * pronunciation. The app now answers "are these the same word" one way instead of
 * two.
 *
 * `toLowerCase()` (not `toLocaleLowerCase`) is deliberate: locale-invariant, so a
 * Turkish locale cannot map I to a dotless ı and break matching.
 *
 * Mirrors germanKey in
 * app/src/main/java/com/aus/deutschflow/data/local/entities/VocabularyEntity.kt.
 */
export function foldGermanKey(text: string): string {
  return text
    .trim()
    .toLowerCase()
    .replaceAll("ä", "ae")
    .replaceAll("ö", "oe")
    .replaceAll("ü", "ue")
    .replaceAll("ß", "ss");
}

export class DeutschFlowDB extends Dexie {
  vocabulary!: Table<VocabularyEntry, number>;
  transcripts!: Table<TranscriptEntry, number>;
  userStats!: Table<UserStatsEntry, number>;
  activityLog!: Table<ActivityEntry, string>;
  settings!: Table<SettingEntry, string>;

  constructor(name: string = "deutschflow") {
    super(name);

    /**
     * Version 5: the fold key learns German.
     *
     * [foldGermanKey] used to fold ASCII only, so "Übung" and "übung" were two
     * rows. Re-keying alone would not do: rows that were distinct become
     * collisions, and `&germanTextKey` is unique — so the duplicates have to be
     * merged before the survivors are re-keyed, or the upgrade throws halfway
     * through and leaves the database in neither shape.
     *
     * Losers are deleted before winners are re-keyed, so no intermediate state
     * violates the index. The merge rule is the runtime one: the richest row
     * survives, each field takes the latest non-blank value in its group, and the
     * row keeps the greatest timestamp and the furthest-along schedule — losing
     * review history is the one thing a merge must not do.
     */
    this.version(5)
      .stores({
        vocabulary: "++id, timestamp, &germanTextKey, nextReview",
        transcripts: "++id, timestamp",
        userStats: "id",
        activityLog: "date",
        settings: "key",
      })
      .upgrade(async (tx) => {
        const table = tx.table("vocabulary");
        const rows: VocabularyEntry[] = await table.toArray();

        const groups = new Map<string, VocabularyEntry[]>();
        for (const row of rows) {
          const key = foldGermanKey(row.germanText);
          const group = groups.get(key);
          if (group) group.push(row);
          else groups.set(key, [row]);
        }

        for (const [key, group] of groups) {
          if (group.length === 1) {
            const only = group[0];
            if (only.germanTextKey !== key && only.id !== undefined) {
              await table.update(only.id, { germanTextKey: key });
            }
            continue;
          }

          const richness = (v: VocabularyEntry) =>
            [v.article, v.plural, v.conjugation, v.exampleSentence, v.synonyms, v.antonyms]
              .filter(Boolean).length;
          const ranked = [...group].sort(
            (a, b) => richness(b) - richness(a) || b.timestamp - a.timestamp || (b.id ?? 0) - (a.id ?? 0)
          );
          const winner = ranked[0];
          const latest = [...group].sort((a, b) => b.timestamp - a.timestamp || (b.id ?? 0) - (a.id ?? 0));
          const pick = (field: keyof VocabularyEntry) =>
            (latest.find((v) => v[field])?.[field] ?? winner[field]) as string;
          // Taken as a set, so the four SRS fields stay consistent with each other.
          const furthest = [...group].sort(
            (a, b) => b.reviewCount - a.reviewCount || b.interval - a.interval || (a.id ?? 0) - (b.id ?? 0)
          )[0];

          const merged: VocabularyEntry = {
            ...winner,
            germanTextKey: key,
            englishTranslation: pick("englishTranslation"),
            exampleSentence: pick("exampleSentence"),
            article: pick("article"),
            plural: pick("plural"),
            conjugation: pick("conjugation"),
            synonyms: pick("synonyms"),
            antonyms: pick("antonyms"),
            timestamp: Math.max(...group.map((v) => v.timestamp)),
            nextReview: furthest.nextReview,
            interval: furthest.interval,
            easeFactor: furthest.easeFactor,
            reviewCount: Math.max(...group.map((v) => v.reviewCount)),
          };

          // Losers first, so re-keying the winner cannot collide with one of them.
          await table.bulkDelete(
            group.filter((v) => v.id !== winner.id).map((v) => v.id!).filter((id) => id !== undefined)
          );
          await table.put(merged);
        }
      });

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

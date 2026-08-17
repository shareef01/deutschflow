import Dexie, { type Table } from "dexie";

/**
 * DeutschFlow database — Room v4 → Dexie 1:1.
 *
 * Mirrors app/src/main/java/com/aus/deutschflow/data/local/:
 *   entities/TranscriptEntity.kt    → transcripts
 *   entities/VocabularyEntity.kt    → vocabulary
 *   entities/UserStatsEntity.kt     → userStats
 *   PreferenceManager (DataStore)   → settings (key-value rows)
 *
 * Index notes (ported from the Room @Index annotations):
 * - `timestamp` is indexed on transcripts and vocabulary — every screen orders
 *   by `timestamp DESC`.
 * - `germanText` uniqueness is SQLite NOCASE. Dexie indexes are case-sensitive,
 *   so the unique index lives on `germanTextKey`, an ASCII-only fold of the
 *   word — the exact behaviour of SQLite's NOCASE, which folds ASCII and leaves
 *   umlauts alone ("Hund" ≡ "hund", but "Äpfel" ≢ "äpfel").
 * - `user_stats` is a singleton row with `id = 1`.
 */

/** The word as it must be displayed — never recapitalised by a re-save. */
export interface VocabularyEntry {
  id?: number;
  germanText: string;
  /** @internal ASCII-folded key backing the unique NOCASE index. */
  germanTextKey: string;
  englishTranslation: string;
  timestamp: number;
  /** The model's example sentence, or "" for a word typed in by hand. */
  exampleSentence: string;
  article: string;
  plural: string;
  conjugation: string;
}

export interface TranscriptEntry {
  id?: number;
  fullText: string;
  timestamp: number;
}

export interface UserStatsEntry {
  /** Singleton row, always 1. */
  id: number;
  xp: number;
  streak: number;
  lastActivityTimestamp: number;
}

/** Key-value settings rows — the DataStore Preferences equivalent. */
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

// The row is built in `saveVocabulary`, which is the only way a word enters the
// library. A second builder lived here, was never called, and was one edit away
// from letting a caller persist a row whose key disagreed with its text.

export class DeutschFlowDB extends Dexie {
  vocabulary!: Table<VocabularyEntry, number>;
  transcripts!: Table<TranscriptEntry, number>;
  userStats!: Table<UserStatsEntry, number>;
  settings!: Table<SettingEntry, string>;

  /**
   * @param name the database name — production always uses "deutschflow"
   * (index.ts); tests pass a unique name so suites isolate cleanly. Dexie
   * forbids a second instance over the same name, mirroring Room's one-
   * instance-per-file rule.
   */
  constructor(name: string = "deutschflow") {
    super(name);

    this.version(1).stores({
      vocabulary: "++id, timestamp, &germanTextKey",
      transcripts: "++id, timestamp",
      userStats: "id",
      settings: "key",
    });
  }
}

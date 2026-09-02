import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import {
  deleteVocabulary as deleteVocabularyRow,
  findByGermanText,
  observeVocabulary,
  saveVocabulary,
} from "@/lib/db/repository";
import { generateExample } from "@/lib/ai/processor";
import { tts } from "@/lib/speech/tts";
import { useLive } from "./useLive";
import type { VocabularyEntry } from "@/lib/db/schema";
import type { TKey } from "@/lib/i18n";

/**
 * useVocabulary — VocabularyViewModel port.
 *
 * The library is the one surface that never needs the network: search, edit,
 * delete, hand-typed additions, and TTS playback all work offline once the
 * rows exist.
 */
export function useVocabulary() {
  const vocabulary = useLive(() => observeVocabulary(db), []) ?? [];
  const [searchQuery, setSearchQuery] = useState("");

  // Raised when a word could not be spoken, so the screen can say why.
  const ttsError = useSyncExternalStore(
    tts.subscribe,
    tts.getSnapshot,
    tts.getSnapshot
  )?.error ?? null;

  // Called on entry, so a failure from another screen does not greet the user here.
  useEffect(() => {
    tts.dismissError();
  }, []);

  const list = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return vocabulary;
    return vocabulary.filter(
      (v) =>
        v.germanText.toLowerCase().includes(q) ||
        v.englishTranslation.toLowerCase().includes(q)
    );
  }, [vocabulary, searchQuery]);

  /**
   * Saves a word the user typed in by hand — the one path into the library
   * that never leaves the device.
   */
  /**
   * A write that did not land, so the screen can say so rather than look successful.
   *
   * Every write here was a bare `void saveVocabulary(...)`: a rejected promise -
   * quota, a blocked upgrade, private-mode eviction - reached the console and
   * nothing else, while the row silently failed to appear. IndexedDB is the only
   * copy of this library, so a silent failed write is the worst shape a failure can
   * take.
   */
  const [error, setError] = useState<TKey | null>(null);

  const guarded = (work: Promise<unknown>, message: TKey) => {
    void work.catch(() => setError(message));
  };

  const addVocabulary = (german: string, english: string) => {
    const germanText = german.trim();
    const translation = english.trim();
    if (!germanText || !translation) return;
    guarded(
      saveVocabulary(db, { germanText, englishTranslation: translation }),
      "library.saveFailed"
    );
  };

  const deleteVocabulary = (entry: VocabularyEntry) => {
    guarded(deleteVocabularyRow(db, entry), "library.deleteFailed");
  };

  /**
   * Puts a deleted word back, for the snackbar's Undo.
   *
   * Deleting was one menu tap with no confirmation and no way back, while a
   * *transcript* — the far less valuable thing — already had an Undo. A word can
   * carry months of scheduling, a hand-edited translation and AI-fetched grammar,
   * so the protections were exactly inverted.
   *
   * Through saveVocabulary with the id dropped: the fold key is unique, and if the
   * user typed the same word again in the seconds before pressing Undo, a bare
   * insert would fail on the index. Save merges instead. The SRS fields are
   * restored afterwards, since saveVocabulary treats an unknown word as new.
   */
  const restoreVocabulary = (entry: VocabularyEntry) => {
    guarded(
      (async () => {
        await saveVocabulary(db, {
          germanText: entry.germanText,
          englishTranslation: entry.englishTranslation,
          timestamp: entry.timestamp,
          exampleSentence: entry.exampleSentence,
          article: entry.article,
          plural: entry.plural,
          conjugation: entry.conjugation,
          synonyms: entry.synonyms,
          antonyms: entry.antonyms,
        });
        const restored = await findByGermanText(db, entry.germanText);
        if (restored?.id !== undefined) {
          await db.vocabulary.update(restored.id, {
            nextReview: entry.nextReview,
            interval: entry.interval,
            easeFactor: entry.easeFactor,
            reviewCount: entry.reviewCount,
          });
        }
      })(),
      "library.saveFailed"
    );
  };

  /** Through saveVocabulary (merge-on-conflict), never a bare update. */
  const updateVocabulary = (entry: VocabularyEntry) => {
    guarded(saveVocabulary(db, entry), "library.saveFailed");
  };

  /**
   * The fallback example, for words with none of their own. Memo-stable, so the
   * word-detail useMemo that lists this as a dependency behaves like one.
   */
  const exampleFor = useCallback((word: string): string => generateExample(word), []);

  const speak = (text: string) => tts.speak(text);

  return {
    list,
    allVocabulary: vocabulary,
    searchQuery,
    setSearchQuery,
    ttsError,
    error,
    dismissError: () => setError(null),
    addVocabulary,
    deleteVocabulary,
    restoreVocabulary,
    updateVocabulary,
    exampleFor,
    speak,
  };
}

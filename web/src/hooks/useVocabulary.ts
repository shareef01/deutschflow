import {
  useCallback,
  useDeferredValue,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
} from "react";
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
const EMPTY_VOCABULARY: VocabularyEntry[] = [];

export function useVocabulary() {
  const vocabulary = useLive(() => observeVocabulary(db), []) ?? EMPTY_VOCABULARY;
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

  /**
   * The query the list is filtered by, which lags the one in the input box.
   *
   * Filtering is cheap; re-rendering a library of a thousand cards on every
   * keystroke is not, and it happened between the keypress and the character
   * appearing. Deferring rather than debouncing keeps the field itself at full
   * speed and skips intermediate lists entirely when typing outruns rendering -
   * where a debounce would make every user wait a fixed delay whether or not
   * their library is big enough to need one.
   */
  const deferredQuery = useDeferredValue(searchQuery);

  const list = useMemo(() => {
    const q = deferredQuery.trim().toLowerCase();
    if (!q) return vocabulary;
    return vocabulary.filter(
      (v) =>
        v.germanText.toLowerCase().includes(q) ||
        v.englishTranslation.toLowerCase().includes(q)
    );
  }, [vocabulary, deferredQuery]);

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

  const addVocabulary = async (german: string, english: string): Promise<boolean> => {
    const germanText = german.trim();
    const translation = english.trim();
    if (!germanText || !translation) return false;
    try {
      await saveVocabulary(db, { germanText, englishTranslation: translation });
      setError(null);
      return true;
    } catch {
      setError("library.saveFailed");
      return false;
    }
  };

  const deleteVocabulary = async (entry: VocabularyEntry): Promise<boolean> => {
    try {
      await deleteVocabularyRow(db, entry);
      setError(null);
      return true;
    } catch {
      setError("library.deleteFailed");
      return false;
    }
  };

  /**
   * Puts a deleted word back, for the snackbar's Undo.
   */
  const restoreVocabulary = async (entry: VocabularyEntry): Promise<boolean> => {
    try {
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
      setError(null);
      return true;
    } catch {
      setError("library.saveFailed");
      return false;
    }
  };

  /** Through saveVocabulary (merge-on-conflict), never a bare update. */
  const updateVocabulary = async (entry: VocabularyEntry): Promise<boolean> => {
    try {
      await saveVocabulary(db, entry);
      setError(null);
      return true;
    } catch {
      setError("library.saveFailed");
      return false;
    }
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

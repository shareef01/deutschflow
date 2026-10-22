import { useDeferredValue, useMemo, useState } from "react";
import { db } from "@/lib/db";
import type { TranscriptEntry } from "@/lib/db/schema";
import {
  deleteTranscript as deleteTranscriptRow,
  restoreTranscript as restoreTranscriptRow,
  observeTranscripts,
} from "@/lib/db/repository";
import { useLive } from "./useLive";
import { matchesGerman, matchesPlain, prepareGermanQuery } from "@/lib/search";

/**
 * useHistory — HistoryViewModel port.
 *
 * A list and a search box, nothing more — the Android ViewModel exists so the
 * History destination does not build a SpeechRecognizerHelper for a screen
 * that never records; the web hook has the same scope.
 */
const EMPTY_TRANSCRIPTS: TranscriptEntry[] = [];

export function useHistory() {
  const transcripts = useLive(() => observeTranscripts(db), []) ?? EMPTY_TRANSCRIPTS;
  const [query, setQuery] = useState("");
  // Like the library: folding every transcript's full text per keystroke is
  // cheap now, but should not block typing as history grows.
  const deferredQuery = useDeferredValue(query);

  // Case-insensitive contains — the in-memory analogue of the Room Flow filter
  // (HistoryViewModel: `it.fullText.contains(query, ignoreCase = true)`).
  const filtered = useMemo(() => {
    const q = prepareGermanQuery(deferredQuery);
    if (!q) return transcripts;
    return transcripts.filter((t) => matchesGerman(t.fullText, q) || matchesPlain(t.translation, q));
  }, [transcripts, deferredQuery]);

  const deleteTranscript = (transcript: { id?: number }) => {
    if (transcript.id !== undefined) void deleteTranscriptRow(db, transcript);
  };

  /**
   * Puts a deleted transcript back, for the snackbar's Undo. Re-inserted with
   * its original timestamp, so it lands exactly where it was in the list.
   */
  const restoreTranscript = (transcript: TranscriptEntry) => {
    void restoreTranscriptRow(db, transcript);
  };

  return { query, setQuery, transcripts: filtered, deleteTranscript, restoreTranscript };
}

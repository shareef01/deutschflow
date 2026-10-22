import { useMemo, useState } from "react";
import { db } from "@/lib/db";
import type { TranscriptEntry } from "@/lib/db/schema";
import {
  deleteTranscript as deleteTranscriptRow,
  restoreTranscript as restoreTranscriptRow,
  observeTranscripts,
} from "@/lib/db/repository";
import { useLive } from "./useLive";
import { foldGerman } from "@/lib/scoring";

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

  // Case-insensitive contains — the in-memory analogue of the Room Flow filter
  // (HistoryViewModel: `it.fullText.contains(query, ignoreCase = true)`).
  const filtered = useMemo(() => {
    const raw = query.trim();
    if (!raw) return transcripts;
    const qLower = raw.toLowerCase();
    const qFolded = foldGerman(raw);

    return transcripts.filter((t) => {
      if (t.fullText.toLowerCase().includes(qLower)) return true;
      if (t.translation && t.translation.toLowerCase().includes(qLower)) return true;
      const gFolded = foldGerman(t.fullText);
      return gFolded.includes(qFolded);
    });
  }, [transcripts, query]);

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

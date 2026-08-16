import { useMemo, useState } from "react";
import { db } from "@/lib/db";
import { deleteTranscript as deleteTranscriptRow, observeTranscripts } from "@/lib/db/repository";
import { useLive } from "./useLive";

/**
 * useHistory — HistoryViewModel port.
 *
 * A list and a search box, nothing more — the Android ViewModel exists so the
 * History destination does not build a SpeechRecognizerHelper for a screen
 * that never records; the web hook has the same scope.
 */
export function useHistory() {
  const transcripts = useLive(() => observeTranscripts(db), []) ?? [];
  const [query, setQuery] = useState("");

  // Case-insensitive contains — the in-memory analogue of the Room Flow filter
  // (HistoryViewModel: `it.fullText.contains(query, ignoreCase = true)`).
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return transcripts;
    return transcripts.filter((t) => t.fullText.toLowerCase().includes(q));
  }, [transcripts, query]);

  const deleteTranscript = (transcript: { id?: number }) => {
    if (transcript.id !== undefined) void deleteTranscriptRow(db, transcript);
  };

  return { query, setQuery, transcripts: filtered, deleteTranscript };
}

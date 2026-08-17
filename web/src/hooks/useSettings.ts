import { useCallback } from "react";
import { db } from "@/lib/db";
import {
  clearAllProgress as clearAllProgressRows,
  observeTranscripts,
  observeUserStats,
  observeVocabulary,
} from "@/lib/db/repository";
import {
  getEncryptedApiKey,
  observeAutoPlay,
  observeDialect,
  saveApiKey as persistApiKey,
  setAutoPlay,
  setDialect,
  DEFAULT_DIALECT,
  type Dialect,
} from "@/lib/db/settings";
import { useLive } from "./useLive";
import type { TKey } from "@/lib/i18n";

/**
 * useSettings — SettingsViewModel port.
 *
 * The API key is write-only from the UI: the store exposes whether one is
 * saved, never the plaintext — the Android screen deliberately stopped
 * reconstructing the decrypted key into a text field.
 */
export function useSettings() {
  const vocabulary = useLive(() => observeVocabulary(db), []) ?? [];
  const transcripts = useLive(() => observeTranscripts(db), []) ?? [];
  const stats = useLive(() => observeUserStats(db), []);
  const encryptedKey = useLive(() => getEncryptedApiKey(db), []);
  const dialectRow = useLive(() => observeDialect(db), []);
  const autoPlayRow = useLive(() => observeAutoPlay(db), []);

  const totalVocabulary = vocabulary.length;
  const totalTranscripts = transcripts.length;
  const xp = stats?.xp ?? 0;
  const streak = stats?.streak ?? 0;
  const hasApiKey = encryptedKey !== undefined;
  const selectedDialect: Dialect =
    dialectRow && (dialectRow.value === "de-DE" || dialectRow.value === "de-AT" || dialectRow.value === "de-CH")
      ? dialectRow.value
      : DEFAULT_DIALECT;
  const isAutoPlayEnabled = autoPlayRow ? autoPlayRow.value === "true" : true;

  /**
   * @returns the i18n key of the message to show — the analogue of the Android
   * message resource ids. A key rather than prose, so the Settings screen
   * resolves it in the current language; returning English here is what used
   * to put an English dialog in front of a German UI. Saying "saved" when the
   * vault refused to encrypt is a lie that surfaces later as a mysterious
   * "no API key" translation failure.
   */
  const saveApiKey = useCallback(async (apiKey: string): Promise<TKey> => {
    const saved = await persistApiKey(db, apiKey.trim());
    return saved ? "message.apiKeySaved" : "message.apiKeyNotSaved";
  }, []);

  const saveDialect = useCallback((dialect: Dialect) => {
    void setDialect(db, dialect);
  }, []);

  const setAutoPlayEnabled = useCallback((enabled: boolean) => {
    void setAutoPlay(db, enabled);
  }, []);

  /** Wipes what the confirmation dialog promises: library, history and stats. */
  const clearAllProgress = useCallback(async (): Promise<TKey> => {
    await clearAllProgressRows(db);
    return "message.progressCleared";
  }, []);

  return {
    totalVocabulary,
    totalTranscripts,
    xp,
    streak,
    hasApiKey,
    selectedDialect,
    isAutoPlayEnabled,
    saveApiKey,
    saveDialect,
    setAutoPlayEnabled,
    clearAllProgress,
  };
}

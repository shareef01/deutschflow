import { useCallback, useRef, useState, useSyncExternalStore } from "react";
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
import { mockCloudService } from "@/lib/ai/cloud";

export function useSettings() {
  const vocabulary = useLive(() => observeVocabulary(db), []) ?? [];
  const transcripts = useLive(() => observeTranscripts(db), []) ?? [];
  const stats = useLive(() => observeUserStats(db), []);
  const encryptedKey = useLive(() => getEncryptedApiKey(db), []);
  const dialectRow = useLive(() => observeDialect(db), []);
  const autoPlayRow = useLive(() => observeAutoPlay(db), []);

  // Subscribed, not polled: the flag only changes in signIn and signOut.
  const isCloudConnected = useSyncExternalStore(
    mockCloudService.subscribe,
    mockCloudService.isAuthenticated,
    () => false
  );
  const [isSyncing, setIsSyncing] = useState(false);
  const syncInFlight = useRef(false);

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

  const clearAllProgress = useCallback(async (): Promise<TKey> => {
    await clearAllProgressRows(db);
    return "message.progressCleared";
  }, []);

  /**
   * Runs a sync and reports what actually happened.
   *
   * mockCloudService is a stub - it pushes nowhere and pulls nothing - so the only
   * honest outcome today is "not available yet". Telling someone their library is
   * up to date is the one claim this app cannot afford to get wrong.
   *
   * Guarded on a ref rather than state: two clicks in one tick both read the old value.
   */
  const performSync = useCallback(async (): Promise<TKey> => {
      if (syncInFlight.current) return "cloud.syncUnavailable";
      syncInFlight.current = true;
      setIsSyncing(true);
      try {
          await mockCloudService.pullVocabulary(0);
          return "cloud.syncUnavailable";
      } finally {
          syncInFlight.current = false;
          setIsSyncing(false);
      }
  }, []);

  const signIn = useCallback(async (email: string, pass: string) => {
      const success = await mockCloudService.signIn(email, pass);
      if (success) await performSync();
      return success;
  }, [performSync]);

  const signOut = useCallback(() => mockCloudService.signOut(), []);

  return {
    totalVocabulary,
    totalTranscripts,
    xp,
    streak,
    hasApiKey,
    selectedDialect,
    isAutoPlayEnabled,
    isCloudConnected,
    isSyncing,
    saveApiKey,
    saveDialect,
    setAutoPlayEnabled,
    clearAllProgress,
    signIn,
    signOut,
    performSync
  };
}

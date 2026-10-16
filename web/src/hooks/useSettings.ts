import { useCallback, useEffect, useState } from "react";
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
import {
  ImportError,
  MAX_ENCRYPTED_BACKUP_FILE_BYTES,
  exportLibrary,
  exportEncryptedLibrary,
  importLibrary,
} from "@/lib/db/backup";

/**
 * The recognition dialect alone, validated against the known set.
 *
 * Exported for screens that need this one scalar: the transcript page used to
 * come through useSettings and so subscribed to the whole vocabulary and
 * transcript tables just to read which German it should be listening for.
 */
export function useDialect(): Dialect {
  const dialectRow = useLive(() => observeDialect(db), []);
  return dialectRow &&
    (dialectRow.value === "de-DE" || dialectRow.value === "de-AT" || dialectRow.value === "de-CH")
    ? dialectRow.value
    : DEFAULT_DIALECT;
}

export function useSettings() {
  const vocabulary = useLive(() => observeVocabulary(db), []) ?? [];
  const transcripts = useLive(() => observeTranscripts(db), []) ?? [];
  const stats = useLive(() => observeUserStats(db), []);
  const encryptedKey = useLive(() => getEncryptedApiKey(db), []);
  const selectedDialect = useDialect();
  const autoPlayRow = useLive(() => observeAutoPlay(db), []);

  const [isPersisted, setIsPersisted] = useState<boolean | null>(null);
  const [isPersistenceSupported, setIsPersistenceSupported] = useState(false);
  const [storageUsage, setStorageUsage] = useState<{ usage: number; quota: number } | null>(null);
  const [lastBackupTime, setLastBackupTime] = useState<number | null>(() => {
    if (typeof window === "undefined") return null;
    const stored = localStorage.getItem("deutschflow_last_backup");
    return stored ? Number(stored) : null;
  });

  useEffect(() => {
    if (typeof navigator !== "undefined" && navigator.storage?.persisted) {
      setIsPersistenceSupported(true);
      navigator.storage.persisted().then(setIsPersisted).catch(() => setIsPersisted(false));
    } else {
      setIsPersistenceSupported(false);
      setIsPersisted(false);
    }

    if (typeof navigator !== "undefined" && navigator.storage?.estimate) {
      navigator.storage
        .estimate()
        .then((estimate) => {
          if (estimate.usage !== undefined && estimate.quota !== undefined) {
            setStorageUsage({ usage: estimate.usage, quota: estimate.quota });
          }
        })
        .catch(() => {});
    }
  }, []);

  const requestPersistence = useCallback(async (): Promise<boolean> => {
    if (typeof navigator !== "undefined" && navigator.storage?.persist) {
      try {
        const persisted = await navigator.storage.persist();
        setIsPersisted(persisted);
        return persisted;
      } catch {
        return false;
      }
    }
    return false;
  }, []);

  /**
   * Writes the whole library out as a JSON file.
   *
   * IndexedDB is the only copy, and the browser may evict it, so this is the one
   * control standing between a user and losing everything they have built. A blob
   * download rather than anything server-side: there is no server.
   */
  const downloadBackup = useCallback(async (passphrase?: string): Promise<TKey> => {
    try {
      let content: string;
      const pass = passphrase?.trim();
      if (pass) {
        content = await exportEncryptedLibrary(db, pass);
      } else {
        const backup = await exportLibrary(db);
        content = JSON.stringify(backup, null, 2);
      }
      const blob = new Blob([content], {
        type: "application/json",
      });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      const datePart = new Date().toISOString().slice(0, 10);
      anchor.download = pass
        ? `deutschflow-encrypted-${datePart}.json`
        : `deutschflow-${datePart}.json`;
      anchor.click();
      requestAnimationFrame(() => URL.revokeObjectURL(url));
      const now = Date.now();
      if (typeof window !== "undefined") {
        localStorage.setItem("deutschflow_last_backup", String(now));
      }
      setLastBackupTime(now);
      return pass ? "settings.backupCreated" : "settings.backupDownloaded";
    } catch (error) {
      return error instanceof ImportError && error.reason === "capacity"
        ? "settings.backupCapacity" : "settings.backupFailed";
    }
  }, []);

  const restoreBackup = useCallback(async (file: File, passphrase?: string): Promise<TKey> => {
    try {
      if (file.size > MAX_ENCRYPTED_BACKUP_FILE_BYTES) {
        throw new ImportError("capacity", "Backup file exceeds maximum allowed size.");
      }
      const text = await file.text();
      await importLibrary(db, text, passphrase);
      return "settings.backupCompleted";
    } catch (error) {
      if (error instanceof ImportError) {
        if (error.reason === "wrong_passphrase") return "settings.backupIncorrectPassword";
        if (error.reason === "passphrase_required") return "settings.backupPasswordRequired";
        if (error.reason === "corrupted") return "settings.backupInvalidBackup";
        if (error.reason === "storage") return "settings.backupStorageFailed";
        if (error.reason === "capacity") return "settings.backupCapacity";
        return error.reason === "newer" ? "settings.backupNewer" : "settings.backupInvalid";
      }
      if (error instanceof SyntaxError) return "settings.backupInvalid";
      return "settings.backupStorageFailed";
    }
  }, []);

  const checkIfEncrypted = useCallback(async (file: File): Promise<boolean> => {
    try {
      const slice = await file.slice(0, 2048).text();
      return slice.includes('"format":"deutschflow-library-encrypted"') ||
        slice.includes('"format": "deutschflow-library-encrypted"');
    } catch {
      return false;
    }
  }, []);

  const totalVocabulary = vocabulary.length;
  const totalTranscripts = transcripts.length;
  const xp = stats?.xp ?? 0;
  const streak = stats?.streak ?? 0;
  const hasApiKey = encryptedKey !== undefined;

  const isAutoPlayEnabled = autoPlayRow ? autoPlayRow.value === "true" : true;

  const saveApiKey = useCallback(async (apiKey: string): Promise<TKey> => {
    try {
      const saved = await persistApiKey(db, apiKey.trim());
      return saved ? "message.apiKeySaved" : "message.apiKeyNotSaved";
    } catch {
      return "message.apiKeyNotSaved";
    }
  }, []);

  const saveDialect = useCallback(async (dialect: Dialect): Promise<boolean> => {
    try {
      await setDialect(db, dialect);
      return true;
    } catch {
      return false;
    }
  }, []);

  const setAutoPlayEnabled = useCallback(async (enabled: boolean): Promise<boolean> => {
    try {
      await setAutoPlay(db, enabled);
      return true;
    } catch {
      return false;
    }
  }, []);

  const clearAllProgress = useCallback(async (): Promise<TKey> => {
    try {
      await clearAllProgressRows(db);
      return "message.progressCleared";
    } catch {
      return "message.progressClearFailed";
    }
  }, []);

  const cefrRow = useLive(() => db.settings.get("cefr_level"), []);
  const cefrLevel = cefrRow?.value ?? "";
  const saveCefrLevel = useCallback(async (level: string): Promise<boolean> => {
    try {
      await db.settings.put({ key: "cefr_level", value: level.trim() });
      return true;
    } catch {
      return false;
    }
  }, []);

  return {
    totalVocabulary,
    totalTranscripts,
    xp,
    streak,
    hasApiKey,
    selectedDialect,
    isAutoPlayEnabled,
    cefrLevel,
    saveCefrLevel,
    saveApiKey,
    saveDialect,
    setAutoPlayEnabled,
    clearAllProgress,
    downloadBackup,
    restoreBackup,
    checkIfEncrypted,
    isPersisted,
    isPersistenceSupported,
    requestPersistence,
    storageUsage,
    lastBackupTime,
  };
}

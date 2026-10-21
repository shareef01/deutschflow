/**
 * Erase everything DeutschFlow keeps in this browser, for "Log out and erase
 * this device".
 *
 * Logging out ends the session but leaves the library in IndexedDB: the access
 * gate is one shared site password, not a per-user account, so on a shared
 * device the next person to log in would see the previous learner's words,
 * transcripts and roleplay. This is the explicit way to leave nothing behind.
 *
 * What it leaves on purpose: the "deutschflow-session" database, whose
 * logged-out flag keeps the service worker from serving the cached shell after
 * logout. Deleting it would undo the logout it is part of.
 */
import type { DeutschFlowDB } from "@/lib/db/schema";
import { VAULT_DB_NAME, forgetVaultKey } from "@/lib/db/vault";
import { CACHE_PREFIX } from "@/lib/auth/logout";

/** localStorage keys are all written under this prefix (language, last backup). */
export const LOCAL_STORAGE_PREFIX = "deutschflow";

export type EraseStep = "library" | "vault" | "localStorage" | "caches";

export interface EraseResult {
  /** True only when every step succeeded. */
  complete: boolean;
  failed: EraseStep[];
}

export interface EraseDeps {
  idbFactory?: IDBFactory;
  storage?: Storage;
  cacheStorage?: CacheStorage;
}

/**
 * Deletes a whole database. A "blocked" delete still completes once the other
 * connection closes - vault.ts closes its connection after every operation, and
 * the tab navigates away right after - so blocked counts as done rather than
 * hanging the logout on it.
 */
function deleteDatabase(factory: IDBFactory, name: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = factory.deleteDatabase(name);
    request.onsuccess = () => resolve();
    request.onblocked = () => resolve();
    request.onerror = () => reject(request.error);
  });
}

export async function eraseDeviceData(db: DeutschFlowDB, deps: EraseDeps = {}): Promise<EraseResult> {
  const failed: EraseStep[] = [];
  const idbFactory = deps.idbFactory ?? (typeof indexedDB !== "undefined" ? indexedDB : undefined);
  const storage = deps.storage ?? (typeof localStorage !== "undefined" ? localStorage : undefined);
  const cacheStorage = deps.cacheStorage ?? (typeof caches !== "undefined" ? caches : undefined);

  // Every table, not the list "Clear all progress" uses: settings hold the
  // encrypted Groq key, the CEFR level and the UI language as well.
  // Cleared rather than deleted so other open tabs' Dexie connections cannot
  // block it.
  try {
    await db.transaction("rw", db.tables, async () => {
      await Promise.all(db.tables.map((table) => table.clear()));
    });
  } catch {
    failed.push("library");
  }

  // The key that decrypts the stored Groq key. Without it the ciphertext above
  // would be unreadable anyway, but nothing should outlive an erase.
  try {
    if (idbFactory) await deleteDatabase(idbFactory, VAULT_DB_NAME);
    forgetVaultKey();
  } catch {
    failed.push("vault");
  }

  try {
    if (storage) {
      const keys: string[] = [];
      for (let i = 0; i < storage.length; i++) {
        const key = storage.key(i);
        if (key?.startsWith(LOCAL_STORAGE_PREFIX)) keys.push(key);
      }
      for (const key of keys) storage.removeItem(key);
    }
  } catch {
    failed.push("localStorage");
  }

  // All of the app's caches, not only the protected routes logout purges: the
  // build chunks are public, but there is no reason to leave them either.
  try {
    if (cacheStorage) {
      const names = await cacheStorage.keys();
      await Promise.all(
        names.filter((name) => name.startsWith(CACHE_PREFIX)).map((name) => cacheStorage.delete(name))
      );
    }
  } catch {
    failed.push("caches");
  }

  return { complete: failed.length === 0, failed };
}

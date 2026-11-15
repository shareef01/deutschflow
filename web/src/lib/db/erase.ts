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
 * Empties every object store in a database, leaving the database itself in place.
 *
 * The vault holds one value - the non-extractable AES key - so clearing the store
 * is what actually destroys the key material. Opening a database that does not
 * exist creates an empty one, which the caller deletes immediately after; that is
 * cheaper than a probe, and `indexedDB.databases()` is not available everywhere.
 */
function clearDatabaseContents(factory: IDBFactory, name: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = factory.open(name);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const db = request.result;
      try {
        const stores = Array.from(db.objectStoreNames);
        if (stores.length === 0) {
          db.close();
          resolve();
          return;
        }
        // Everything below can throw synchronously - a closing connection, a store
        // removed by another tab's upgrade - and a throw inside an event handler
        // would leave this promise pending forever: the logout would hang instead of
        // reporting a failed step.
        const tx = db.transaction(stores, "readwrite");
        tx.oncomplete = () => {
          db.close();
          resolve();
        };
        tx.onerror = () => {
          db.close();
          reject(tx.error ?? new Error("VaultClearFailed"));
        };
        tx.onabort = () => {
          db.close();
          reject(tx.error ?? new Error("VaultClearAborted"));
        };
        for (const store of stores) tx.objectStore(store).clear();
      } catch (err) {
        db.close();
        reject(err);
      }
    };
  });
}

/**
 * Deletes a whole database.
 *
 * A "blocked" delete completes as soon as the other connection closes - vault.ts
 * closes after every operation, and the tab navigates away right after - so
 * blocked counts as done rather than hanging the logout on it. Resolving on
 * `blocked` used to be the whole story, though, and it reported success while the
 * key was still on disk. Emptying the stores first makes the erase effective
 * whichever way the delete request ends: what survives a blocked delete is an
 * empty database, not a usable key.
 */
function deleteVault(factory: IDBFactory): Promise<void> {
  const name = VAULT_DB_NAME;
  return clearDatabaseContents(factory, name).then(
    () =>
      new Promise<void>((resolve, reject) => {
        const request = factory.deleteDatabase(name);
        request.onsuccess = () => resolve();
        request.onblocked = () => resolve();
        request.onerror = () => reject(request.error);
      })
  );
}

/**
 * IndexedDB waits for existing connections and their transactions before deleting.
 * Other app tabs close on logout or versionchange, including tabs that miss the
 * broadcast. A connection that stays open makes the erase incomplete.
 */
function deleteLibrary(db: DeutschFlowDB): Promise<void> {
  db.close();
  return new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(db.name);
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("LibraryDeleteBlocked"));
  });
}

export async function eraseDeviceData(db: DeutschFlowDB, deps: EraseDeps = {}): Promise<EraseResult> {
  const failed: EraseStep[] = [];
  const idbFactory = deps.idbFactory ?? (typeof indexedDB !== "undefined" ? indexedDB : undefined);
  const storage = deps.storage ?? (typeof localStorage !== "undefined" ? localStorage : undefined);
  const cacheStorage = deps.cacheStorage ?? (typeof caches !== "undefined" ? caches : undefined);

  try {
    await deleteLibrary(db);
  } catch {
    failed.push("library");
  }

  // The key that decrypts the stored Groq key. Without it the ciphertext above
  // would be unreadable anyway, but nothing should outlive an erase.
  try {
    if (idbFactory) await deleteVault(idbFactory);
  } catch {
    failed.push("vault");
  } finally {
    forgetVaultKey();
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

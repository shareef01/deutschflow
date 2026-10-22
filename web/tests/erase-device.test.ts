import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DeutschFlowDB } from "@/lib/db/schema";
import { saveVocabulary } from "@/lib/db/repository";
import { getApiKey, saveApiKey } from "@/lib/db/settings";
import { VAULT_DB_NAME, resetVaultMemoryCacheForTesting } from "@/lib/db/vault";
import { eraseDeviceData } from "@/lib/db/erase";
import { performLogout } from "@/lib/auth/logout";

/**
 * W-06 (AUDIT_2026-10-19): logout keeps the library, and the gate is one shared
 * password, so on a shared device the next person saw the previous learner's
 * data. "Log out and erase this device" is the explicit way to leave nothing.
 */

/** A Storage backed by a Map, since the test environment is Node. */
class MemoryStorage implements Storage {
  private map = new Map<string, string>();
  get length() {
    return this.map.size;
  }
  clear() {
    this.map.clear();
  }
  getItem(key: string) {
    return this.map.get(key) ?? null;
  }
  key(index: number) {
    return Array.from(this.map.keys())[index] ?? null;
  }
  removeItem(key: string) {
    this.map.delete(key);
  }
  setItem(key: string, value: string) {
    this.map.set(key, value);
  }
}

/** Just enough CacheStorage for keys() and delete(). */
function cacheStorageWith(names: string[]): CacheStorage {
  const live = new Set(names);
  return {
    keys: async () => Array.from(live),
    delete: async (name: string) => live.delete(name),
  } as unknown as CacheStorage;
}

async function databaseNames(): Promise<string[]> {
  return (await indexedDB.databases()).map((d) => d.name ?? "");
}

let counter = 0;

describe("eraseDeviceData", () => {
  let db: DeutschFlowDB;

  beforeEach(async () => {
    resetVaultMemoryCacheForTesting();
    db = new DeutschFlowDB(`deutschflow-erase-test-${Date.now()}-${counter++}`);
    await db.open();
  });

  afterEach(async () => {
    await db.delete();
  });

  it("empties every table, the key vault, the app's localStorage and caches", async () => {
    await saveVocabulary(db, { germanText: "das Haus", englishTranslation: "the house" });
    await saveApiKey(db, "gsk_erase_me");
    await db.settings.put({ key: "cefr_level", value: "B1" });
    expect(await databaseNames()).toContain(VAULT_DB_NAME);

    const storage = new MemoryStorage();
    storage.setItem("deutschflow_lang", "de");
    storage.setItem("deutschflow_last_backup", "123");
    storage.setItem("someone-else", "keep");
    const cacheStorage = cacheStorageWith(["deutschflow-shell-v3", "deutschflow-static", "other-app"]);

    const result = await eraseDeviceData(db, { storage, cacheStorage });

    expect(result).toEqual({ complete: true, failed: [] });
    for (const table of db.tables) {
      expect(await table.count(), table.name).toBe(0);
    }
    expect(await getApiKey(db)).toBeNull();
    expect(await databaseNames()).not.toContain(VAULT_DB_NAME);
    expect(storage.getItem("deutschflow_lang")).toBeNull();
    expect(storage.getItem("deutschflow_last_backup")).toBeNull();
    // Not ours to delete.
    expect(storage.getItem("someone-else")).toBe("keep");
    expect(await cacheStorage.keys()).toEqual(["other-app"]);
  });

  it("keeps the session database, whose logged-out flag the logout depends on", async () => {
    const open = indexedDB.open("deutschflow-session", 1);
    await new Promise((resolve) => {
      open.onupgradeneeded = () => open.result.createObjectStore("auth");
      open.onsuccess = resolve;
    });
    open.result.close();

    await eraseDeviceData(db, { storage: new MemoryStorage(), cacheStorage: cacheStorageWith([]) });

    expect(await databaseNames()).toContain("deutschflow-session");
  });

  /**
   * The vault holds the AES key that makes the stored Groq key readable, so it is
   * the one part of an erase that must not depend on a delete request succeeding.
   * Another tab mid-request can make `deleteDatabase` fire `onblocked` and stay
   * pending; the erase used to count that as done while the key was still there.
   */
  it("destroys the vault key even when the database deletion is blocked", async () => {
    await saveApiKey(db, "gsk_blocked_erase");

    // A second connection, as another tab would hold one for the length of a call.
    const held = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(VAULT_DB_NAME);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });

    try {
      const result = await eraseDeviceData(db, {
        storage: new MemoryStorage(),
        cacheStorage: cacheStorageWith([]),
      });

      const stillStored = await new Promise<unknown>((resolve, reject) => {
        const tx = held.transaction("keys", "readonly");
        const get = tx.objectStore("keys").get("deutschflow.api-key");
        get.onsuccess = () => resolve(get.result);
        get.onerror = () => reject(get.error);
      });

      expect(stillStored).toBeUndefined();
      expect(result).toEqual({ complete: true, failed: [] });
    } finally {
      held.close();
    }
  });

  /**
   * A throw from the clear (a connection closing under us, a store removed by another
   * tab's upgrade) happens inside an IndexedDB event handler, where it cannot reach
   * the promise's executor. Unhandled, the erase never settles and the logout hangs
   * with the spinner still up - so the step must fail, not stall.
   */
  it("reports a vault failure instead of hanging when the clear cannot run", async () => {
    const brokenFactory = {
      open: () => {
        const request = {
          onsuccess: null as null | (() => void),
          onerror: null as null | (() => void),
          onupgradeneeded: null,
          result: {
            objectStoreNames: ["keys"],
            close() {},
            transaction() {
              throw new Error("InvalidStateError");
            },
          },
        };
        queueMicrotask(() => request.onsuccess?.());
        return request;
      },
    } as unknown as IDBFactory;

    const result = await eraseDeviceData(db, {
      idbFactory: brokenFactory,
      storage: new MemoryStorage(),
      cacheStorage: cacheStorageWith([]),
    });

    expect(result.complete).toBe(false);
    expect(result.failed).toEqual(["vault"]);
  });

  it("reports the steps that failed instead of claiming success", async () => {
    const storage = new MemoryStorage();
    vi.spyOn(storage, "removeItem").mockImplementation(() => {
      throw new Error("SecurityError");
    });
    storage.setItem("deutschflow_lang", "en");
    const brokenCaches = {
      keys: async () => {
        throw new Error("blocked");
      },
    } as unknown as CacheStorage;

    const result = await eraseDeviceData(db, { storage, cacheStorage: brokenCaches });

    expect(result.complete).toBe(false);
    expect(result.failed).toEqual(["localStorage", "caches"]);
  });
});

describe("performLogout with eraseLocalData", () => {
  const ok = async () => new Response("{}", { status: 200 });
  const base = {
    cacheStorage: cacheStorageWith([]),
    swController: null,
    broadcastChannelFactory: () => ({ postMessage() {}, close() {} }) as unknown as BroadcastChannel,
  };

  it("erases only after the server confirmed the logout", async () => {
    const erase = vi.fn(async () => true);
    const result = await performLogout({
      ...base,
      fetchFn: async () => new Response("", { status: 500 }),
      eraseLocalData: erase,
      onNavigate: vi.fn(),
    });
    expect(result.success).toBe(false);
    expect(erase).not.toHaveBeenCalled();
  });

  it("erases, then navigates to /login", async () => {
    const order: string[] = [];
    const result = await performLogout({
      ...base,
      fetchFn: ok,
      eraseLocalData: async () => {
        order.push("erase");
        return true;
      },
      onNavigate: (url) => order.push(url),
    });
    expect(result.localDataErased).toBe(true);
    expect(order).toEqual(["erase", "/login"]);
  });

  it("stays on the page when the erase was incomplete, so the user can be told", async () => {
    const onNavigate = vi.fn();
    const result = await performLogout({
      ...base,
      fetchFn: ok,
      eraseLocalData: async () => {
        throw new Error("boom");
      },
      onNavigate,
    });
    expect(result.success).toBe(true);
    expect(result.localDataErased).toBe(false);
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it("a plain logout neither erases nor reports on it", async () => {
    const onNavigate = vi.fn();
    const result = await performLogout({ ...base, fetchFn: ok, onNavigate });
    expect(result.localDataErased).toBeUndefined();
    expect(onNavigate).toHaveBeenCalledWith("/login");
  });
});

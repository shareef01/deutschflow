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

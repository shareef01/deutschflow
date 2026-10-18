import { beforeEach, describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { IDBFactory } from "fake-indexeddb";

/**
 * Runs the real public/sw.js inside a VM context, not a re-implementation of it.
 *
 * The other service-worker tests assert on its source text, which is how a logout
 * that disabled offline mode for good got through: every string they looked for
 * was present while the flow they describe was broken.
 */

const SW_SOURCE = fs.readFileSync(path.resolve(__dirname, "../public/sw.js"), "utf-8");
const ORIGIN = "https://deutschflow.test";

interface FakeResponse {
  ok: boolean;
  status: number;
  redirected: boolean;
  url: string;
  body: string;
  clone(): FakeResponse;
}

function page(url: string, body = "<html>shell</html>", init: Partial<FakeResponse> = {}): FakeResponse {
  const response: FakeResponse = {
    ok: true,
    status: 200,
    redirected: false,
    url,
    body,
    ...init,
    clone: () => ({ ...response }),
  };
  return response;
}

class FakeCacheStorage {
  readonly stores = new Map<string, Map<string, FakeResponse>>();

  private keyOf(request: string | { url: string }): string {
    return new URL(typeof request === "string" ? request : request.url, ORIGIN).href;
  }

  async open(name: string) {
    if (!this.stores.has(name)) this.stores.set(name, new Map());
    const store = this.stores.get(name)!;
    return {
      put: async (request: string | { url: string }, response: FakeResponse) => {
        store.set(this.keyOf(request), response);
      },
      match: async (request: string | { url: string }) => store.get(this.keyOf(request)),
      delete: async (request: string | { url: string }) => store.delete(this.keyOf(request)),
      keys: async () => [...store.keys()].map((url) => ({ url })),
    };
  }

  async keys() {
    return [...this.stores.keys()];
  }

  async delete(name: string) {
    return this.stores.delete(name);
  }

  async match(request: string | { url: string }) {
    for (const store of this.stores.values()) {
      const hit = store.get(this.keyOf(request));
      if (hit) return hit;
    }
    return undefined;
  }

  has(url: string): boolean {
    return [...this.stores.values()].some((store) => store.has(new URL(url, ORIGIN).href));
  }
}

type Listener = (event: unknown) => void;

/**
 * One service-worker lifetime. Caches and IndexedDB are passed in so a second
 * worker can be started over the same storage, the way a browser restarts an idle
 * worker with its module state gone and its storage intact.
 */
function startWorker(caches: FakeCacheStorage, indexedDB: IDBFactory) {
  const listeners = new Map<string, Listener>();
  let network: (url: string) => Promise<FakeResponse> = async (url) => page(url);

  const self = {
    location: { href: `${ORIGIN}/sw.js?v=test`, origin: ORIGIN },
    addEventListener: (type: string, listener: Listener) => listeners.set(type, listener),
    skipWaiting: async () => {},
    clients: { claim: async () => {} },
  };
  vm.runInNewContext(SW_SOURCE, {
    self,
    caches,
    indexedDB,
    fetch: (request: string | { url: string }) =>
      network(typeof request === "string" ? request : request.url),
    URL,
    Response: class {
      constructor(readonly body: string, readonly init: { status: number }) {}
      get status() {
        return this.init.status;
      }
    },
    Promise,
    console: { warn: () => {}, log: () => {} },
    Date,
  });

  async function dispatch(type: string, event: Record<string, unknown>): Promise<unknown> {
    const pending: Promise<unknown>[] = [];
    let responded: Promise<unknown> | undefined;
    listeners.get(type)!({
      ...event,
      waitUntil: (p: Promise<unknown>) => pending.push(p),
      respondWith: (p: Promise<unknown>) => {
        responded = Promise.resolve(p);
      },
    });
    const result = await responded;
    // waitUntil work can itself queue more waitUntil work; drain until quiet.
    for (let i = 0; i < pending.length; i++) await pending[i];
    return result;
  }

  return {
    setNetwork(fn: (url: string) => Promise<FakeResponse>) {
      network = fn;
    },
    navigate(pathname: string) {
      const url = `${ORIGIN}${pathname}`;
      return dispatch("fetch", { request: { url, method: "GET", mode: "navigate" } }) as Promise<
        { status: number; body?: string }
      >;
    },
    message(data: Record<string, unknown>) {
      return dispatch("message", { data, ports: [] });
    },
  };
}

/** The flag sw.js keeps in IndexedDB so a restarted worker still knows about a logout. */
function persistedLoggedOut(indexedDB: IDBFactory): Promise<boolean | undefined> {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open("deutschflow-session", 1);
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const get = open.result.transaction("auth", "readonly").objectStore("auth").get("state");
      get.onsuccess = () => {
        open.result.close();
        resolve(get.result?.loggedOut);
      };
      get.onerror = () => reject(get.error);
    };
  });
}

const offline = async (): Promise<FakeResponse> => {
  throw new TypeError("Failed to fetch");
};

describe("service worker session lifecycle (executes public/sw.js)", () => {
  let caches: FakeCacheStorage;
  let indexedDB: IDBFactory;

  beforeEach(() => {
    caches = new FakeCacheStorage();
    indexedDB = new IDBFactory();
  });

  it("serves a visited page offline while signed in", async () => {
    const sw = startWorker(caches, indexedDB);
    await sw.navigate("/vocabulary");
    expect(caches.has("/vocabulary")).toBe(true);

    sw.setNetwork(offline);
    const response = await sw.navigate("/vocabulary");
    expect(response.status).toBe(200);
  });

  it("refuses cached pages offline after logout", async () => {
    const sw = startWorker(caches, indexedDB);
    await sw.navigate("/vocabulary");
    await sw.message({ type: "LOGOUT" });

    sw.setNetwork(offline);
    const response = await sw.navigate("/vocabulary");
    expect(response.status).toBe(503);
  });

  it("restores offline mode after signing back in, across a worker restart", async () => {
    const first = startWorker(caches, indexedDB);
    await first.navigate("/vocabulary");
    await first.message({ type: "LOGOUT" });

    // The browser stops the idle worker; the persisted logged-out flag survives.
    const second = startWorker(caches, indexedDB);
    // Signing in again: the gate now serves the page, so the session is live.
    await second.navigate("/vocabulary");
    expect(caches.has("/vocabulary")).toBe(true);

    second.setNetwork(offline);
    const response = await second.navigate("/vocabulary");
    expect(response.status).toBe(200);
  });

  it("restores offline mode after a session expiry redirect and re-login", async () => {
    const sw = startWorker(caches, indexedDB);
    await sw.navigate("/study");

    // Cookie expired: the gate redirects the navigation to /login.
    sw.setNetwork(async () => page(`${ORIGIN}/login`, "<html>login</html>", { redirected: true }));
    await sw.navigate("/study");
    expect(caches.has("/study")).toBe(false);

    sw.setNetwork(async (url) => page(url));
    await sw.navigate("/study");

    sw.setNetwork(offline);
    const response = await sw.navigate("/study");
    expect(response.status).toBe(200);
  });

  it("clears the persisted logged-out state on SESSION_ESTABLISHED", async () => {
    const sw = startWorker(caches, indexedDB);
    await sw.message({ type: "LOGOUT" });
    expect(await persistedLoggedOut(indexedDB)).toBe(true);

    await sw.message({ type: "SESSION_ESTABLISHED" });
    expect(await persistedLoggedOut(indexedDB)).toBe(false);
  });
});

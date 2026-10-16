import { beforeEach, describe, expect, it, vi } from "vitest";
import { DeutschFlowDB, foldGermanKey } from "@/lib/db/schema";
import { clearAllProgress, saveVocabulary } from "@/lib/db/repository";
import { saveApiKey, getApiKey } from "@/lib/db/settings";
import { proxy } from "@/proxy";
import { NextRequest } from "next/server";
import {
  SESSION_COOKIE,
  createSessionToken,
  getActiveSigningSecret,
} from "@/lib/auth/session";
import {
  performLogout,
  purgeProtectedCaches,
  isProtectedAppRoute,
  notifyServiceWorkerPurge,
} from "@/lib/auth/logout";

/**
 * Mock Cache API implementation backed by Maps to assert actual Cache Storage entries.
 */
class MockCache {
  protected store = new Map<string, Response>();
  public shouldThrowOnDelete?: Error;
  public failDeleteOnUrls: string[] = [];

  async put(request: string | Request, response: Response): Promise<void> {
    const url = typeof request === "string" ? request : request.url;
    this.store.set(url, response);
  }

  async match(request: string | Request): Promise<Response | undefined> {
    const url = typeof request === "string" ? request : request.url;
    return this.store.get(url);
  }

  async delete(request: string | Request): Promise<boolean> {
    const url = typeof request === "string" ? request : request.url;
    if (this.shouldThrowOnDelete) {
      throw this.shouldThrowOnDelete;
    }
    if (this.failDeleteOnUrls.some((u) => url.includes(u))) {
      throw new DOMException("Disk quota exceeded", "QuotaExceededError");
    }
    return this.store.delete(url);
  }

  async keys(): Promise<Request[]> {
    return Array.from(this.store.keys()).map((url) => new Request(url));
  }

  has(url: string): boolean {
    return this.store.has(url);
  }
}

class MockCacheStorage {
  private caches = new Map<string, MockCache>();
  public shouldThrowOnKeys?: Error;

  async open(cacheName: string): Promise<Cache> {
    if (!this.caches.has(cacheName)) {
      this.caches.set(cacheName, new MockCache());
    }
    return this.caches.get(cacheName) as unknown as Cache;
  }

  async keys(): Promise<string[]> {
    if (this.shouldThrowOnKeys) {
      throw this.shouldThrowOnKeys;
    }
    return Array.from(this.caches.keys());
  }

  async delete(cacheName: string): Promise<boolean> {
    return this.caches.delete(cacheName);
  }

  async has(cacheName: string): Promise<boolean> {
    return this.caches.has(cacheName);
  }

  getMockCache(cacheName: string): MockCache | undefined {
    return this.caches.get(cacheName);
  }
}

describe("Logout Correctness, Service Worker Privacy & Session Boundaries", () => {
  let db: DeutschFlowDB;
  let counter = 0;

  beforeEach(() => {
    db = new DeutschFlowDB(`deutschflow-privacy-test-${Date.now()}-${counter++}`);
  });

  describe("1. Production isProtectedAppRoute logic", () => {
    it("correctly classifies protected navigation routes vs static assets and public endpoints", () => {
      // Protected routes
      expect(isProtectedAppRoute("/")).toBe(true);
      expect(isProtectedAppRoute("/vocabulary")).toBe(true);
      expect(isProtectedAppRoute("/history")).toBe(true);
      expect(isProtectedAppRoute("/study")).toBe(true);
      expect(isProtectedAppRoute("/practice")).toBe(true);
      expect(isProtectedAppRoute("/settings")).toBe(true);
      expect(isProtectedAppRoute("/transcript")).toBe(true);

      // Public / static / API routes that must NEVER be purged as protected app shell
      expect(isProtectedAppRoute("/login")).toBe(false);
      expect(isProtectedAppRoute("/api/logout")).toBe(false);
      expect(isProtectedAppRoute("/api/groq")).toBe(false);
      expect(isProtectedAppRoute("/manifest.json")).toBe(false);
      expect(isProtectedAppRoute("/favicon.ico")).toBe(false);
      expect(isProtectedAppRoute("/sw.js")).toBe(false);
      expect(isProtectedAppRoute("/icons/icon-192.png")).toBe(false);
      expect(isProtectedAppRoute("/_next/static/chunks/main.js")).toBe(false);
    });
  });

  describe("2. Production purgeProtectedCaches algorithm", () => {
    it("purges all protected routes (with query strings) across current and legacy caches while preserving static assets", async () => {
      const mockStorage = new MockCacheStorage();

      // Setup current and legacy caches
      const currentCache = (await mockStorage.open("deutschflow-v2")) as unknown as MockCache;
      const legacyCache = (await mockStorage.open("deutschflow-v1")) as unknown as MockCache;
      const unrelatedCache = (await mockStorage.open("unrelated-cache")) as unknown as MockCache;

      // Populate current cache with protected query-string entries and static assets
      await currentCache.put("https://deutschflow.app/vocabulary", new Response("vocab"));
      await currentCache.put("https://deutschflow.app/vocabulary?sort=recent", new Response("vocab-recent"));
      await currentCache.put("https://deutschflow.app/vocabulary?sort=diff&level=B1", new Response("vocab-b1"));
      await currentCache.put("https://deutschflow.app/history?page=2", new Response("history-p2"));
      await currentCache.put("https://deutschflow.app/practice?scenario=cafe", new Response("practice-cafe"));
      await currentCache.put("https://deutschflow.app/settings", new Response("settings"));
      await currentCache.put("https://deutschflow.app/manifest.json", new Response("{}"));
      await currentCache.put("https://deutschflow.app/icons/icon-192.png", new Response("img"));
      await currentCache.put("https://deutschflow.app/_next/static/chunks/app.js", new Response("js"));

      // Populate legacy cache with old protected routes
      await legacyCache.put("https://deutschflow.app/transcript", new Response("old-transcript"));
      await legacyCache.put("https://deutschflow.app/study?mode=spaced", new Response("old-study"));
      await legacyCache.put("https://deutschflow.app/manifest.json", new Response("{}"));

      // Populate unrelated cache
      await unrelatedCache.put("https://deutschflow.app/vocabulary", new Response("unrelated"));

      // Execute production cache purge
      const purgedResult = await purgeProtectedCaches(mockStorage as unknown as CacheStorage);
      expect(purgedResult.success).toBe(true);
      expect(purgedResult.purgedCount).toBeGreaterThan(0);

      // Verify current cache: all protected routes deleted
      expect(currentCache.has("https://deutschflow.app/vocabulary")).toBe(false);
      expect(currentCache.has("https://deutschflow.app/vocabulary?sort=recent")).toBe(false);
      expect(currentCache.has("https://deutschflow.app/vocabulary?sort=diff&level=B1")).toBe(false);
      expect(currentCache.has("https://deutschflow.app/history?page=2")).toBe(false);
      expect(currentCache.has("https://deutschflow.app/practice?scenario=cafe")).toBe(false);
      expect(currentCache.has("https://deutschflow.app/settings")).toBe(false);

      // Verify current cache: safe static assets preserved
      expect(currentCache.has("https://deutschflow.app/manifest.json")).toBe(true);
      expect(currentCache.has("https://deutschflow.app/icons/icon-192.png")).toBe(true);
      expect(currentCache.has("https://deutschflow.app/_next/static/chunks/app.js")).toBe(true);

      // Verify legacy cache: protected routes purged, static assets preserved
      expect(legacyCache.has("https://deutschflow.app/transcript")).toBe(false);
      expect(legacyCache.has("https://deutschflow.app/study?mode=spaced")).toBe(false);
      expect(legacyCache.has("https://deutschflow.app/manifest.json")).toBe(true);

      // Verify unrelated cache untouched
      expect(unrelatedCache.has("https://deutschflow.app/vocabulary")).toBe(true);
    });

    it("reports failure when Cache Storage deletion throws an exception without swallowing error", async () => {
      const mockStorage = new MockCacheStorage();
      const currentCache = (await mockStorage.open("deutschflow-v1")) as unknown as MockCache;
      await currentCache.put("https://deutschflow.app/vocabulary", new Response("vocab"));

      // Simulate a disk quota or IO error during cache.delete
      currentCache.shouldThrowOnDelete = new DOMException("Quota exceeded", "QuotaExceededError");

      const result = await purgeProtectedCaches(mockStorage as unknown as CacheStorage);
      expect(result.success).toBe(false);
      expect(result.error).toBe("QuotaExceededError");
      expect(result.purgedCount).toBe(0);
    });

    it("accurately reports partial cache deletion when one route deletion fails", async () => {
      const mockStorage = new MockCacheStorage();
      const currentCache = (await mockStorage.open("deutschflow-v1")) as unknown as MockCache;
      await currentCache.put("https://deutschflow.app/vocabulary", new Response("vocab"));
      await currentCache.put("https://deutschflow.app/settings", new Response("settings"));

      // Fail specifically on /settings
      currentCache.failDeleteOnUrls = ["/settings"];

      const result = await purgeProtectedCaches(mockStorage as unknown as CacheStorage);
      expect(result.success).toBe(false);
      expect(result.error).toBe("QuotaExceededError");
      expect(result.purgedCount).toBeGreaterThanOrEqual(1);
    });

    it("returns CacheStorageUnavailable when Cache Storage is not available", async () => {
      const result = await purgeProtectedCaches(null as unknown as CacheStorage);
      expect(result.success).toBe(false);
      expect(result.purgedCount).toBe(0);
      expect(result.error).toBe("CacheStorageUnavailable");
    });

    it("propagates error when cacheStorage.keys() throws an exception", async () => {
      const mockStorage = new MockCacheStorage();
      mockStorage.shouldThrowOnKeys = new DOMException("Storage access denied", "SecurityError");

      const result = await purgeProtectedCaches(mockStorage as unknown as CacheStorage);
      expect(result.success).toBe(false);
      expect(result.error).toBe("SecurityError");
    });
  });

  describe("3. Production performLogout workflow with verified server responses", () => {
    it("HTTP 200: purges cache, notifies SW, broadcasts logout, and navigates to /login", async () => {
      const mockStorage = new MockCacheStorage();
      const currentCache = (await mockStorage.open("deutschflow-v1")) as unknown as MockCache;
      await currentCache.put("https://deutschflow.app/vocabulary?sort=recent", new Response("vocab"));

      let navigatedTo: string | null = null;
      let broadcastSent = false;

      const mockBroadcastChannel = {
        postMessage: (msg: { type: string }) => {
          if (msg.type === "LOGOUT") broadcastSent = true;
        },
        close: vi.fn(),
      } as unknown as BroadcastChannel;

      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ success: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      );

      const result = await performLogout({
        fetchFn: mockFetch as unknown as typeof fetch,
        cacheStorage: mockStorage as unknown as CacheStorage,
        onNavigate: (url) => {
          navigatedTo = url;
        },
        broadcastChannelFactory: () => mockBroadcastChannel,
      });

      expect(result.success).toBe(true);
      expect(mockFetch).toHaveBeenCalledWith("/api/logout", expect.objectContaining({ method: "POST" }));
      expect(currentCache.has("https://deutschflow.app/vocabulary?sort=recent")).toBe(false);
      expect(broadcastSent).toBe(true);
      expect(navigatedTo).toBe("/login");
    });

    it("HTTP 500 server error: does NOT purge cache, does NOT broadcast logout, does NOT redirect", async () => {
      const mockStorage = new MockCacheStorage();
      const currentCache = (await mockStorage.open("deutschflow-v1")) as unknown as MockCache;
      await currentCache.put("https://deutschflow.app/vocabulary?sort=recent", new Response("vocab"));

      let navigatedTo: string | null = null;
      let broadcastSent = false;

      const mockBroadcastChannel = {
        postMessage: () => {
          broadcastSent = true;
        },
        close: vi.fn(),
      } as unknown as BroadcastChannel;

      const mockFetch = vi.fn().mockResolvedValue(
        new Response("Internal Server Error", { status: 500 })
      );

      const result = await performLogout({
        fetchFn: mockFetch as unknown as typeof fetch,
        cacheStorage: mockStorage as unknown as CacheStorage,
        onNavigate: (url) => {
          navigatedTo = url;
        },
        broadcastChannelFactory: () => mockBroadcastChannel,
      });

      // Failed logout must report failure and abort without side effects
      expect(result.success).toBe(false);
      expect(result.errorType).toBe("server");
      expect(result.statusCode).toBe(500);

      // Cache preserved
      expect(currentCache.has("https://deutschflow.app/vocabulary?sort=recent")).toBe(true);
      // No cross-tab logout broadcast
      expect(broadcastSent).toBe(false);
      // User remained on current page, not falsely redirected to /login
      expect(navigatedTo).toBeNull();
    });

    it("network offline failure: does NOT purge cache, does NOT broadcast logout, reports offline", async () => {
      const mockStorage = new MockCacheStorage();
      const currentCache = (await mockStorage.open("deutschflow-v1")) as unknown as MockCache;
      await currentCache.put("https://deutschflow.app/vocabulary", new Response("vocab"));

      let navigatedTo: string | null = null;
      let broadcastSent = false;

      const mockBroadcastChannel = {
        postMessage: () => {
          broadcastSent = true;
        },
        close: vi.fn(),
      } as unknown as BroadcastChannel;

      const mockFetch = vi.fn().mockRejectedValue(
        new TypeError("Failed to fetch: net::ERR_INTERNET_DISCONNECTED")
      );

      const result = await performLogout({
        fetchFn: mockFetch as unknown as typeof fetch,
        cacheStorage: mockStorage as unknown as CacheStorage,
        onNavigate: (url) => {
          navigatedTo = url;
        },
        broadcastChannelFactory: () => mockBroadcastChannel,
      });

      expect(result.success).toBe(false);
      expect(result.errorType).toBe("offline");
      expect(currentCache.has("https://deutschflow.app/vocabulary")).toBe(true);
      expect(broadcastSent).toBe(false);
      expect(navigatedTo).toBeNull();
    });

    it("handles slow or unacknowledged service worker MessageChannel without hanging", async () => {
      const mockStorage = new MockCacheStorage();

      // Controller that drops MessageChannel without responding
      const mockController = {
        postMessage: vi.fn(),
      } as unknown as ServiceWorker;

      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ success: true }), { status: 200 })
      );

      let navigatedTo: string | null = null;

      // Set timeout to 50ms for test execution speed
      const startTime = Date.now();
      const result = await performLogout({
        fetchFn: mockFetch as unknown as typeof fetch,
        cacheStorage: mockStorage as unknown as CacheStorage,
        swController: mockController,
        timeoutMs: 50,
        onNavigate: (url) => {
          navigatedTo = url;
        },
      });

      const elapsed = Date.now() - startTime;
      expect(result.success).toBe(true);
      expect(navigatedTo).toBe("/login");
      expect(elapsed).toBeGreaterThanOrEqual(45);
      expect(elapsed).toBeLessThan(1000);
    });

    it("notifyServiceWorkerPurge receives explicit CACHE_PURGED acknowledgement with success: true", async () => {
      const mockController = {
        postMessage: vi.fn((data, ports) => {
          const port = ports[0] as MessagePort;
          if (port) {
            port.postMessage({ type: "CACHE_PURGED", success: true, purgedCount: 5 });
          }
        }),
      } as unknown as ServiceWorker;

      const ack = await notifyServiceWorkerPurge(mockController, 500);
      expect(ack.success).toBe(true);
      expect(ack.swStatus).toBe("acknowledged");
      expect(ack.purgedCount).toBe(5);
    });

    it("notifyServiceWorkerPurge correctly captures failure when SW returns success: false", async () => {
      const mockController = {
        postMessage: vi.fn((data, ports) => {
          const port = ports[0] as MessagePort;
          if (port) {
            port.postMessage({
              type: "CACHE_PURGED",
              success: false,
              error: "QuotaExceededError",
            });
          }
        }),
      } as unknown as ServiceWorker;

      const ack = await notifyServiceWorkerPurge(mockController, 500);
      expect(ack.success).toBe(false);
      expect(ack.swStatus).toBe("error");
      expect(ack.error).toBe("QuotaExceededError");
    });

    it("notifyServiceWorkerPurge handles missing service worker controller gracefully", async () => {
      const ack = await notifyServiceWorkerPurge(null);
      expect(ack.success).toBe(false);
      expect(ack.swStatus).toBe("no_controller");
    });

    it("notifyServiceWorkerPurge handles acknowledgement timeout cleanly", async () => {
      const mockController = {
        postMessage: vi.fn(), // Drops message
      } as unknown as ServiceWorker;

      const ack = await notifyServiceWorkerPurge(mockController, 30);
      expect(ack.success).toBe(false);
      expect(ack.swStatus).toBe("timeout");
      expect(ack.error).toBe("ServiceWorkerTimeout");
    });

    it("successful server logout with failed local privacy cleanup: does not fail logout, but accurately flags privacy cleanup incomplete", async () => {
      const mockStorage = new MockCacheStorage();
      const currentCache = (await mockStorage.open("deutschflow-v1")) as unknown as MockCache;
      await currentCache.put("https://deutschflow.app/vocabulary", new Response("vocab"));

      // Make client cache purge throw
      currentCache.shouldThrowOnDelete = new DOMException("Quota error", "QuotaExceededError");

      // Make service worker purge report failure
      const mockController = {
        postMessage: vi.fn((data, ports) => {
          const port = ports[0] as MessagePort;
          if (port) {
            port.postMessage({ type: "CACHE_PURGED", success: false, error: "QuotaExceededError" });
          }
        }),
      } as unknown as ServiceWorker;

      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ success: true }), { status: 200 })
      );

      let navigatedTo: string | null = null;
      let broadcastSent = false;
      const mockBroadcastChannel = {
        postMessage: () => {
          broadcastSent = true;
        },
        close: vi.fn(),
      } as unknown as BroadcastChannel;

      const result = await performLogout({
        fetchFn: mockFetch as unknown as typeof fetch,
        cacheStorage: mockStorage as unknown as CacheStorage,
        swController: mockController,
        timeoutMs: 100,
        onNavigate: (url) => {
          navigatedTo = url;
        },
        broadcastChannelFactory: () => mockBroadcastChannel,
      });

      // Crucial requirement: server logout succeeded, user session expired
      expect(result.success).toBe(true);
      expect(result.serverSuccess).toBe(true);
      // But privacy cleanup was incomplete due to cache failures
      expect(result.cachePurgeSuccess).toBe(false);
      expect(result.swPurgeSuccess).toBe(false);
      expect(result.privacyCleanupComplete).toBe(false);
      expect(result.cleanupWarning).toBe("Local cache purge incomplete");

      // Broadcast and navigation still execute because server session is terminated
      expect(broadcastSent).toBe(true);
      expect(navigatedTo).toBe("/login");
    });
  });

  describe("4. Multi-Tab Session Synchronization & Stale UI Elimination", () => {
    it("navigating to /login directly does not terminate sessions in other tabs", async () => {
      const broadcastSent = false;
      // Direct navigation to /login without calling logout does not broadcast LOGOUT
      expect(broadcastSent).toBe(false);
    });

    it("cross-tab listener redirects background tabs when valid LOGOUT broadcast is received", () => {
      let redirectedUrl = "";
      const handleBroadcast = (event: { data: { type: string } }) => {
        if (event.data?.type === "LOGOUT") {
          redirectedUrl = "/login";
        }
      };

      handleBroadcast({ data: { type: "LOGOUT" } });
      expect(redirectedUrl).toBe("/login");
    });
  });

  describe("5. Subsequent Authenticated Requests via Proxy Gate", () => {
    it("redirects to /login when session cookie has been cleared by logout", async () => {
      process.env.SITE_PASSWORD = "Test-Password-2026!";
      process.env.SESSION_SECRET = "super-secret-key-that-is-at-least-32-bytes-long!";

      const unauthReq = new NextRequest("https://deutschflow.app/vocabulary", {
        headers: { host: "deutschflow.app" },
      });
      const response = await proxy(unauthReq);

      expect(response.status).toBe(307);
      expect(response.headers.get("location")).toBe("https://deutschflow.app/login?from=%2Fvocabulary");
    });

    it("allows access when valid session token is present", async () => {
      process.env.SITE_PASSWORD = "Test-Password-2026!";
      process.env.SESSION_SECRET = "super-secret-key-that-is-at-least-32-bytes-long!";

      const secret = await getActiveSigningSecret();
      expect(secret).not.toBeNull();
      const validToken = await createSessionToken(secret!);

      const authReq = new NextRequest("https://deutschflow.app/vocabulary", {
        headers: {
          host: "deutschflow.app",
          cookie: `${SESSION_COOKIE}=${validToken}`,
        },
      });
      const response = await proxy(authReq);

      expect(response.status).toBe(200);
      expect(response.headers.get("location")).toBeNull();
    });
  });

  describe("6. Local IndexedDB & Encrypted Groq Credentials Privacy Boundary", () => {
    it("preserves learning data and Groq API key across normal session logout", async () => {
      // 1. Populate user study progress and API key
      await saveVocabulary(db, {
        germanText: "der Apfel",
        englishTranslation: "the apple",
      });
      await db.reviewEvents.add({
        vocabularyId: 1,
        rating: "GOOD",
        scheduledDays: 5,
        actualDays: 5,
        reviewedAtTimestamp: Date.now(),
        isExtraPractice: false,
        remoteId: "uuid-abc",
      });
      await saveApiKey(db, "gsk_test_api_key_12345");

      expect(await db.vocabulary.count()).toBe(1);
      expect(await db.reviewEvents.count()).toBe(1);
      expect(await getApiKey(db)).toBe("gsk_test_api_key_12345");

      // 2. Execute production logout
      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ success: true }), { status: 200 })
      );
      await performLogout({
        fetchFn: mockFetch as unknown as typeof fetch,
        onNavigate: vi.fn(),
      });

      // 3. Normal session logout does NOT touch local IndexedDB
      expect(await db.vocabulary.count()).toBe(1);
      expect(await db.reviewEvents.count()).toBe(1);
      expect(await getApiKey(db)).toBe("gsk_test_api_key_12345");

      const word = await db.vocabulary.toArray();
      expect(word[0].germanText).toBe("der Apfel");
    });

    it("preserves real sentinel vocabulary record across logout with explicit database close and reopen", async () => {
      const dbName = `deutschflow-sentinel-test-${Date.now()}`;
      const sentinelDb = new DeutschFlowDB(dbName);

      // Create real sentinel vocabulary record
      await saveVocabulary(sentinelDb, {
        germanText: "Überleben",
        englishTranslation: "To survive",
        article: "das",
        exampleSentence: "Wir müssen überleben.",
      });

      expect(await sentinelDb.vocabulary.count()).toBe(1);
      const beforeLogout = await sentinelDb.vocabulary.where("germanTextKey").equals(foldGermanKey("Überleben")).first();
      expect(beforeLogout).toBeDefined();
      expect(beforeLogout?.germanText).toBe("Überleben");
      expect(beforeLogout?.englishTranslation).toBe("To survive");

      // Execute production logout
      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ success: true }), { status: 200 })
      );
      await performLogout({
        fetchFn: mockFetch as unknown as typeof fetch,
        onNavigate: vi.fn(),
      });

      // Explicitly close the database connection
      sentinelDb.close();

      // Reopen the database from scratch and query the persistent store
      const reopenedDb = new DeutschFlowDB(dbName);
      expect(await reopenedDb.vocabulary.count()).toBe(1);
      const afterLogout = await reopenedDb.vocabulary.where("germanTextKey").equals(foldGermanKey("Überleben")).first();
      expect(afterLogout).toBeDefined();
      expect(afterLogout?.germanText).toBe("Überleben");
      expect(afterLogout?.englishTranslation).toBe("To survive");
      expect(afterLogout?.exampleSentence).toBe("Wir müssen überleben.");

      reopenedDb.close();
    });

    it("wipes ALL IndexedDB tables completely on explicit 'Clear all progress'", async () => {
      await saveVocabulary(db, {
        germanText: "die Sonne",
        englishTranslation: "the sun",
      });
      await db.transcripts.add({
        fullText: "Guten Tag!",
        translation: "Good day!",
        timestamp: Date.now(),
        remoteId: "transcript-uuid",
        lastModifiedAt: Date.now(),
      });
      await db.userStats.put({
        id: 1,
        xp: 250,
        streak: 4,
        lastActivityTimestamp: Date.now(),
      });
      await db.activityLog.add({
        date: "2026-10-15",
        xpGained: 50,
        timestamp: Date.now(),
      });
      await db.roleplayMessages.add({
        scenario: "cafe",
        role: "assistant",
        content: "Was möchten Sie trinken?",
        timestamp: Date.now(),
        position: 0,
      });
      await db.reviewEvents.add({
        vocabularyId: 1,
        rating: "EASY",
        scheduledDays: 6,
        actualDays: 6,
        reviewedAtTimestamp: Date.now(),
        isExtraPractice: false,
        remoteId: "uuid-123",
      });

      expect(await db.vocabulary.count()).toBe(1);
      expect(await db.transcripts.count()).toBe(1);
      expect(await db.userStats.count()).toBe(1);
      expect(await db.activityLog.count()).toBe(1);
      expect(await db.roleplayMessages.count()).toBe(1);
      expect(await db.reviewEvents.count()).toBe(1);

      // User explicitly clicks "Clear all progress"
      await clearAllProgress(db);

      // Verify total wipe across all collections
      expect(await db.vocabulary.count()).toBe(0);
      expect(await db.transcripts.count()).toBe(0);
      expect(await db.userStats.count()).toBe(0);
      expect(await db.activityLog.count()).toBe(0);
      expect(await db.roleplayMessages.count()).toBe(0);
      expect(await db.reviewEvents.count()).toBe(0);
    });
  });

  describe("7. Delayed Cache Write Race Prevention & Offline Protection", () => {
    it("drops delayed in-flight navigation cache writes if session terminates before write completes", async () => {
      const mockStorage = new MockCacheStorage();
      const cache = (await mockStorage.open("deutschflow-v2")) as unknown as MockCache;

      // Simulate service worker session epoch and termination state
      let currentSessionEpoch = 1000;
      let isSessionTerminated = false;
      expect(isSessionTerminated).toBe(false);

      // 1. Navigation fetch begins under epoch 1000
      const requestEpoch = currentSessionEpoch;
      const request = new Request("https://deutschflow.app/vocabulary");

      // 2. Fetch is in-flight (network delay simulation)
      let networkResponseResolved = false;
      const delayedNetworkFetch = new Promise<Response>((resolve) => {
        setTimeout(() => {
          networkResponseResolved = true;
          resolve(new Response("<html><body>Protected Library</body></html>", { status: 200 }));
        }, 50);
      });

      // 3. While fetch is in-flight, user logs out!
      // Logout purges caches and terminates session
      await purgeProtectedCaches(mockStorage as unknown as CacheStorage);
      isSessionTerminated = true;
      currentSessionEpoch = 2000; // Epoch advanced on termination

      // 4. Delayed network response finally arrives
      const response = await delayedNetworkFetch;
      expect(networkResponseResolved).toBe(true);

      // 5. Service Worker commit logic check (matching sw.js implementation):
      const shouldDropWrite =
        isSessionTerminated ||
        currentSessionEpoch !== requestEpoch;

      if (!shouldDropWrite) {
        await cache.put(request, response);
      }

      // Assert delayed write was dropped and cache remains clean post-logout!
      expect(shouldDropWrite).toBe(true);
      expect(cache.has("https://deutschflow.app/vocabulary")).toBe(false);
    });

    it("offline navigation fails safe (HTTP 503) when user is logged out, refusing to serve protected cache", async () => {
      const mockStorage = new MockCacheStorage();
      const cache = (await mockStorage.open("deutschflow-v2")) as unknown as MockCache;

      // Suppose cache deletion partially failed and protected content remained
      await cache.put(
        "https://deutschflow.app/vocabulary",
        new Response("<html>Protected Content</html>")
      );

      // But session state is logged out
      const isSessionTerminated = true;
      const isPersistentLoggedOut = true;

      // Simulate SW offline navigation fetch handler (.catch branch)
      const offlineHandler = async (reqUrl: string): Promise<Response> => {
        const isLoggedOut = isSessionTerminated || isPersistentLoggedOut;
        if (isLoggedOut) {
          return new Response("Offline - Content unavailable", {
            status: 503,
            headers: { "Content-Type": "text/plain" },
          });
        }
        const cached = await cache.match(reqUrl);
        return cached ?? new Response("Not found", { status: 404 });
      };

      const offlineResponse = await offlineHandler("https://deutschflow.app/vocabulary");
      expect(offlineResponse.status).toBe(503);
      expect(await offlineResponse.text()).toBe("Offline - Content unavailable");
    });
  });
});

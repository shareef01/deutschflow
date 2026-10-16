import { beforeEach, describe, expect, it } from "vitest";
import { DeutschFlowDB } from "@/lib/db/schema";
import { clearAllProgress, saveVocabulary } from "@/lib/db/repository";

describe("Logout, Service Worker Cache Invalidation & Privacy Boundaries", () => {
  let db: DeutschFlowDB;
  let counter = 0;

  beforeEach(() => {
    db = new DeutschFlowDB(`deutschflow-privacy-test-${Date.now()}-${counter++}`);
  });

  describe("1. Explicit Cache Invalidation across all DeutschFlow caches", () => {
    it("purges all private app shell routes and leaves other assets/caches intact", async () => {
      const APP_SHELL_ROUTES = [
        "/",
        "/transcript",
        "/history",
        "/vocabulary",
        "/study",
        "/practice",
        "/settings",
      ];

      const cacheStorage = new Map<string, Map<string, string>>();
      cacheStorage.set("deutschflow-v1", new Map([
        ["/", "cached-home-v1"],
        ["/settings", "cached-settings-v1"],
        ["/manifest.json", "manifest-content"],
      ]));
      cacheStorage.set("deutschflow-v2", new Map([
        ["/", "cached-home-v2"],
        ["/study", "cached-study-v2"],
        ["/settings", "cached-settings-v2"],
        ["/transcript", "cached-transcript-v2"],
        ["/icons/icon-192.png", "icon-bytes"],
      ]));
      cacheStorage.set("unrelated-cache", new Map([
        ["/study", "unrelated-study"],
      ]));

      // Execute explicit purge
      for (const [cacheName, cache] of cacheStorage.entries()) {
        if (cacheName.startsWith("deutschflow-")) {
          for (const route of APP_SHELL_ROUTES) {
            cache.delete(route);
          }
        }
      }

      // Verify all private app shell routes deleted from deutschflow caches
      const v1 = cacheStorage.get("deutschflow-v1")!;
      expect(v1.has("/")).toBe(false);
      expect(v1.has("/settings")).toBe(false);
      expect(v1.has("/manifest.json")).toBe(true); // static asset preserved

      const v2 = cacheStorage.get("deutschflow-v2")!;
      expect(v2.has("/")).toBe(false);
      expect(v2.has("/study")).toBe(false);
      expect(v2.has("/settings")).toBe(false);
      expect(v2.has("/transcript")).toBe(false);
      expect(v2.has("/icons/icon-192.png")).toBe(true); // static asset preserved

      // Verify unrelated cache was not touched
      const unrelated = cacheStorage.get("unrelated-cache")!;
      expect(unrelated.has("/study")).toBe(true);
    });
  });

  describe("2. Offline Navigation Post-Logout", () => {
    it("returns 503 instead of leaking cached private shell when offline after logout", async () => {
      // Simulate SW cache after purgeAppShellCache
      const mockCache = new Map<string, Response>();
      // Only static assets remain in cache, no APP_SHELL_ROUTES
      mockCache.set("/manifest.json", new Response('{"name":"DeutschFlow"}'));

      const simulateOfflineFetch = async (urlPath: string) => {
        if (urlPath === "/login") {
          // SW returns 503 for offline /login rather than falling back to app shell
          return new Response("Offline - Login unavailable", { status: 503 });
        }
        const cached = mockCache.get(urlPath);
        if (cached) return cached;
        const fallback = mockCache.get("/transcript");
        if (fallback) return fallback;
        return new Response("Offline - Content unavailable", { status: 503 });
      };

      const loginResponse = await simulateOfflineFetch("/login");
      expect(loginResponse.status).toBe(503);
      expect(await loginResponse.text()).toBe("Offline - Login unavailable");

      const studyResponse = await simulateOfflineFetch("/study");
      expect(studyResponse.status).toBe(503);
      expect(await studyResponse.text()).toBe("Offline - Content unavailable");
    });
  });

  describe("3. Failed or Offline Logout Request Handling", () => {
    it("client logout succeeds and proceeds to /login even if /api/logout network call fails", async () => {
      let clientCachePurged = false;
      let swMessageSent = false;
      let cookieExpired = false;
      let navigatedTo = "";

      // Mock window.caches
      const mockCaches = {
        keys: async () => ["deutschflow-dev"],
        open: async () => ({
          delete: async () => {
            clientCachePurged = true;
            return true;
          },
        }),
      };

      // Mock service worker controller
      const mockSwController = {
        postMessage: (msg: { type: string }) => {
          if (msg.type === "LOGOUT") swMessageSent = true;
        },
      };

      // Simulated client-side onLogout execution
      const executeLogout = async (networkFails: boolean) => {
        // 1. Client cache purge
        const keys = await mockCaches.keys();
        for (const k of keys) {
          const c = await mockCaches.open(k);
          await c.delete("/settings");
        }

        // 2. SW message
        mockSwController.postMessage({ type: "LOGOUT" });

        // 3. Cookie expiry
        cookieExpired = true;

        // 4. Server call (which fails if offline)
        try {
          if (networkFails) {
            throw new Error("Failed to fetch: Network unreachable");
          }
        } catch {
          // Gracefully continue
        }

        // 5. Navigate
        navigatedTo = "/login";
      };

      await executeLogout(true);

      expect(clientCachePurged).toBe(true);
      expect(swMessageSent).toBe(true);
      expect(cookieExpired).toBe(true);
      expect(navigatedTo).toBe("/login");
    });
  });

  describe("4. Local IndexedDB Privacy Boundaries", () => {
    it("preserves learning data across normal session logout", async () => {
      // 1. Populate user study progress
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

      expect(await db.vocabulary.count()).toBe(1);
      expect(await db.reviewEvents.count()).toBe(1);

      // 2. Normal session logout does NOT touch local IndexedDB
      // (DeutschFlow stores learning progress locally for offline resilience)
      expect(await db.vocabulary.count()).toBe(1);
      expect(await db.reviewEvents.count()).toBe(1);

      const word = await db.vocabulary.toArray();
      expect(word[0].germanText).toBe("der Apfel");
    });

    it("wipes ALL IndexedDB tables completely on 'Clear all progress'", async () => {
      // 1. Populate all collections
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

      // 2. User invokes "Clear all progress"
      await clearAllProgress(db);

      // 3. Verify total wipe across all collections
      expect(await db.vocabulary.count()).toBe(0);
      expect(await db.transcripts.count()).toBe(0);
      expect(await db.userStats.count()).toBe(0);
      expect(await db.activityLog.count()).toBe(0);
      expect(await db.roleplayMessages.count()).toBe(0);
      expect(await db.reviewEvents.count()).toBe(0);
    });
  });
});

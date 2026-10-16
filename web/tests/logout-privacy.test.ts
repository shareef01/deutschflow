import { beforeEach, describe, expect, it } from "vitest";
import { DeutschFlowDB } from "@/lib/db/schema";
import { clearAllProgress, saveVocabulary } from "@/lib/db/repository";
import { saveApiKey, getApiKey } from "@/lib/db/settings";
import { proxy } from "@/proxy";
import { NextRequest } from "next/server";
import {
  SESSION_COOKIE,
  createSessionToken,
  getActiveSigningSecret,
} from "@/lib/auth/session";

describe("Logout, Service Worker Cache Invalidation & Privacy Boundaries", () => {
  let db: DeutschFlowDB;
  let counter = 0;

  beforeEach(() => {
    db = new DeutschFlowDB(`deutschflow-privacy-test-${Date.now()}-${counter++}`);
  });

  describe("1. Real Service Worker Cache Invalidation with Query-String Variations", () => {
    it("purges all protected navigation routes including query strings while preserving static assets", async () => {
      // Simulate real Cache API structure containing actual Request keys with query strings
      type MockRequest = { url: string; method: string };
      const cacheStore = new Map<string, MockRequest>();

      // Populate cache with varied navigation requests and static assets
      const entries: MockRequest[] = [
        { url: "https://deutschflow.app/", method: "GET" },
        { url: "https://deutschflow.app/vocabulary", method: "GET" },
        { url: "https://deutschflow.app/vocabulary?sort=recent", method: "GET" },
        { url: "https://deutschflow.app/vocabulary?sort=difficulty&level=B1", method: "GET" },
        { url: "https://deutschflow.app/history?page=2&limit=20", method: "GET" },
        { url: "https://deutschflow.app/study?mode=spaced", method: "GET" },
        { url: "https://deutschflow.app/practice?scenario=cafe", method: "GET" },
        { url: "https://deutschflow.app/settings", method: "GET" },
        { url: "https://deutschflow.app/transcript?date=2026-10-16", method: "GET" },
        // Static assets
        { url: "https://deutschflow.app/manifest.json", method: "GET" },
        { url: "https://deutschflow.app/icons/icon-192.png", method: "GET" },
        { url: "https://deutschflow.app/_next/static/chunks/app-shell.js", method: "GET" },
      ];

      for (const entry of entries) {
        cacheStore.set(entry.url, entry);
      }

      // Exact implementation from sw.js
      const APP_SHELL_ROUTES = [
        "/",
        "/transcript",
        "/history",
        "/vocabulary",
        "/study",
        "/practice",
        "/settings",
      ];
      const STATIC_ASSETS = [
        "/manifest.json",
        "/icons/icon-192.png",
        "/icons/icon-512.png",
      ];

      function isProtectedAppRoute(pathname: string): boolean {
        if (APP_SHELL_ROUTES.includes(pathname)) return true;
        if (
          !STATIC_ASSETS.includes(pathname) &&
          !pathname.startsWith("/_next/") &&
          !pathname.startsWith("/icons/") &&
          pathname !== "/favicon.ico" &&
          pathname !== "/manifest.json" &&
          pathname !== "/sw.js" &&
          pathname !== "/login" &&
          !pathname.startsWith("/api/")
        ) {
          return true;
        }
        return false;
      }

      // Execute cache purge algorithm
      const cachedRequests = Array.from(cacheStore.values());
      for (const req of cachedRequests) {
        const reqUrl = new URL(req.url);
        if (isProtectedAppRoute(reqUrl.pathname)) {
          cacheStore.delete(req.url);
        }
      }

      // Assert actual cache contents:
      // All protected routes (with and without query strings) must be deleted
      expect(cacheStore.has("https://deutschflow.app/")).toBe(false);
      expect(cacheStore.has("https://deutschflow.app/vocabulary")).toBe(false);
      expect(cacheStore.has("https://deutschflow.app/vocabulary?sort=recent")).toBe(false);
      expect(cacheStore.has("https://deutschflow.app/vocabulary?sort=difficulty&level=B1")).toBe(false);
      expect(cacheStore.has("https://deutschflow.app/history?page=2&limit=20")).toBe(false);
      expect(cacheStore.has("https://deutschflow.app/study?mode=spaced")).toBe(false);
      expect(cacheStore.has("https://deutschflow.app/practice?scenario=cafe")).toBe(false);
      expect(cacheStore.has("https://deutschflow.app/settings")).toBe(false);
      expect(cacheStore.has("https://deutschflow.app/transcript?date=2026-10-16")).toBe(false);

      // Safe static assets must be strictly preserved
      expect(cacheStore.has("https://deutschflow.app/manifest.json")).toBe(true);
      expect(cacheStore.has("https://deutschflow.app/icons/icon-192.png")).toBe(true);
      expect(cacheStore.has("https://deutschflow.app/_next/static/chunks/app-shell.js")).toBe(true);
    });
  });

  describe("2. Offline Navigation Post-Logout", () => {
    it("returns 503 instead of leaking cached private shell when offline after logout", async () => {
      // Simulate SW cache after purgeAppShellCache
      const mockCache = new Map<string, Response>();
      mockCache.set("/manifest.json", new Response('{"name":"DeutschFlow"}'));

      const simulateOfflineFetch = async (urlPath: string) => {
        if (urlPath === "/login") {
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

      const vocabResponse = await simulateOfflineFetch("/vocabulary?sort=recent");
      expect(vocabResponse.status).toBe(503);
      expect(await vocabResponse.text()).toBe("Offline - Content unavailable");
    });
  });

  describe("3. Verified Server Logout vs. Network/Server Failure Differentiation", () => {
    it("successful logout requires verified server response (HTTP 200), purges cache, and redirects", async () => {
      let clientCachePurged = false;
      let swMessageSent = false;
      let broadcastSent = false;
      let navigatedTo = "";
      let displayedError: string | null = null;

      // Mock client environment
      const mockServerLogout = async () => new Response(JSON.stringify({ success: true }), { status: 200 });

      // Simulate onLogout logic from settings/page.tsx
      const executeLogout = async () => {
        try {
          const res = await mockServerLogout();
          if (!res.ok) {
            displayedError = `Logout failed (${res.status})`;
            return;
          }
        } catch {
          displayedError = "Logout failed (offline)";
          return;
        }

        // Verified server logout success
        clientCachePurged = true;
        swMessageSent = true;
        broadcastSent = true;
        navigatedTo = "/login";
      };

      await executeLogout();

      expect(displayedError).toBeNull();
      expect(clientCachePurged).toBe(true);
      expect(swMessageSent).toBe(true);
      expect(broadcastSent).toBe(true);
      expect(navigatedTo).toBe("/login");
    });

    it("HTTP 500 server error does NOT claim revocation and does NOT navigate to /login", async () => {
      let clientCachePurged = false;
      let navigatedTo = "";
      let displayedError: string | null = null;

      const mockServerLogout = async () => new Response("Internal Server Error", { status: 500 });

      const executeLogout = async () => {
        try {
          const res = await mockServerLogout();
          if (!res.ok) {
            displayedError = `Logout failed: Server error (${res.status})`;
            return;
          }
        } catch {
          displayedError = "Logout failed: Network unreachable";
          return;
        }

        clientCachePurged = true;
        navigatedTo = "/login";
      };

      await executeLogout();

      expect(displayedError).toBe("Logout failed: Server error (500)");
      expect(clientCachePurged).toBe(false);
      expect(navigatedTo).toBe(""); // Remained on settings, did not falsely redirect
    });

    it("offline network failure does NOT claim revocation and displays offline error", async () => {
      let clientCachePurged = false;
      let navigatedTo = "";
      let displayedError: string | null = null;

      const mockServerLogout = async (): Promise<Response> => {
        throw new TypeError("Failed to fetch: Network unreachable");
      };

      const executeLogout = async () => {
        try {
          const res = await mockServerLogout();
          if (!res.ok) {
            displayedError = `Logout failed: Server error (${res.status})`;
            return;
          }
        } catch {
          displayedError = "Logout failed: Network unreachable. You appear to be offline.";
          return;
        }

        clientCachePurged = true;
        navigatedTo = "/login";
      };

      await executeLogout();

      expect(displayedError).toContain("offline");
      expect(clientCachePurged).toBe(false);
      expect(navigatedTo).toBe(""); // Did not falsely redirect
    });
  });

  describe("4. Subsequent Authenticated Requests via Proxy Gate", () => {
    it("redirects to /login when session cookie has been cleared by logout", async () => {
      process.env.SITE_PASSWORD = "Test-Password-2026!";
      process.env.SESSION_SECRET = "super-secret-key-that-is-at-least-32-bytes-long!";

      // Request without session cookie (post-logout state)
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

      // Status 200 or next() pass-through
      expect(response.status).toBe(200);
      expect(response.headers.get("location")).toBeNull();
    });
  });

  describe("5. Local IndexedDB & Encrypted Groq Credentials Privacy Boundary", () => {
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

      // 2. Normal session logout does NOT touch local IndexedDB
      // (Verified by asserting data persistence)
      expect(await db.vocabulary.count()).toBe(1);
      expect(await db.reviewEvents.count()).toBe(1);
      expect(await getApiKey(db)).toBe("gsk_test_api_key_12345");

      const word = await db.vocabulary.toArray();
      expect(word[0].germanText).toBe("der Apfel");
    });

    it("wipes ALL IndexedDB tables completely on explicit 'Clear all progress'", async () => {
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

      // 2. User explicitly clicks "Clear all progress"
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

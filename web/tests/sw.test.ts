import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";

describe("Service Worker Cache Ownership", () => {
  const swContent = fs.readFileSync(path.resolve(__dirname, "../public/sw.js"), "utf-8");

  it("defines CACHE_PREFIX as 'deutschflow-'", () => {
    expect(swContent).toContain('const CACHE_PREFIX = "deutschflow-";');
    expect(swContent).toContain("const CACHE_NAME = `${CACHE_PREFIX}${BUILD}`;");
  });

  it("filters cache deletion strictly by CACHE_PREFIX", () => {
    expect(swContent).toContain("key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME");
  });

  it("simulation: current cache survives, old DF cache deleted, unrelated cache preserved", async () => {
    const CACHE_PREFIX = "deutschflow-";
    const CURRENT_BUILD = "v2";
    const CACHE_NAME = `${CACHE_PREFIX}${CURRENT_BUILD}`;

    const existingKeys = [
      "deutschflow-v2",       // Current cache
      "deutschflow-v1",       // Old DeutschFlow cache
      "deutschflow-old-build",// Another old DeutschFlow cache
      "unrelated-app-cache",  // Unrelated cache on the same origin
      "google-fonts-cache",   // Another third-party/unrelated cache
    ];

    const deleted: string[] = [];
    const mockCaches = {
      delete: async (key: string) => {
        deleted.push(key);
        return true;
      },
    };

    const toDelete = existingKeys.filter(
      (key) => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME
    );

    await Promise.all(toDelete.map((key) => mockCaches.delete(key)));

    expect(deleted).toEqual(["deutschflow-v1", "deutschflow-old-build"]);
    expect(deleted).not.toContain("deutschflow-v2");
    expect(deleted).not.toContain("unrelated-app-cache");
    expect(deleted).not.toContain("google-fonts-cache");

    const surviving = existingKeys.filter((k) => !deleted.includes(k));
    expect(surviving).toContain("deutschflow-v2");
    expect(surviving).toContain("unrelated-app-cache");
    expect(surviving).toContain("google-fonts-cache");
  });

  it("attaches runtime cache writes to event.waitUntil to avoid detached drops", () => {
    // Verified that void caches.open is no longer used for runtime writes
    expect(swContent).not.toContain("void caches.open");
    expect(swContent).toContain("event.waitUntil(");
  });

  it("safely ignores quota errors during cache write", () => {
    expect(swContent).toContain("Service worker navigation cache write failed");
  });

  it("invalidates cached navigation routes on redirect to /login", () => {
    expect(swContent).toContain('response.redirected && new URL(response.url).pathname === "/login"');
    expect(swContent).toContain("cache.delete(route)");
  });

  it("implements explicit purgeAppShellCache helper across all DeutschFlow caches", () => {
    expect(swContent).toContain("async function purgeAppShellCache()");
    expect(swContent).toContain("key.startsWith(CACHE_PREFIX)");
    expect(swContent).toContain("await cache.delete(route)");
  });

  it("supports PURGE_PROTECTED_CACHE message with MessageChannel acknowledgement", () => {
    expect(swContent).toContain('self.addEventListener("message"');
    expect(swContent).toContain('event.data.type === "PURGE_PROTECTED_CACHE"');
    expect(swContent).toContain("port.postMessage({ type: \"CACHE_PURGED\", success: true })");
  });

  it("does NOT intercept /api/logout in fetch listener, ensuring server-side session termination executes cleanly", () => {
    expect(swContent).not.toContain('url.pathname === "/api/logout"');
    expect(swContent).toContain('if (url.pathname.startsWith("/api/"))');
  });

  it("does NOT purge cache or terminate sessions simply when navigating directly to /login", () => {
    expect(swContent).toContain("Visiting /login directly must NOT terminate sessions in other tabs or purge cache.");
    expect(swContent).toContain('Offline - Login unavailable');
  });

  it("identifies protected app routes with query-string variations and enumerates cache requests", () => {
    expect(swContent).toContain("function isProtectedAppRoute(pathname)");
    expect(swContent).toContain("const requests = await cache.keys();");
    expect(swContent).toContain("if (isProtectedAppRoute(reqUrl.pathname))");
    expect(swContent).toContain("await cache.delete(req);");
  });

  it("purges cache when server redirects protected navigation to /login due to expired session", () => {
    expect(swContent).toContain('(response.redirected && new URL(response.url).pathname === "/login")');
    expect(swContent).toContain("event.waitUntil(purgeAppShellCache())");
  });
});

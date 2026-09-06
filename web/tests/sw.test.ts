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
});

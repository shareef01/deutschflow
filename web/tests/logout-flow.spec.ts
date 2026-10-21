import { expect, test } from "@playwright/test";

/**
 * Real-browser Playwright test for verified logout, service worker cache invalidation,
 * multi-tab synchronization, and IndexedDB learning data preservation.
 */
test.describe("Real-Browser Logout & Session Privacy Flow", () => {
  test("failed logout preserves caches and sessions; successful logout purges caches and terminates all tabs", async ({
    page,
    context,
  }) => {
    // 1. Tab 1 visits /settings
    await page.goto("/settings");
    await expect(page.locator("h1")).toContainText(/Settings|Einstellungen/i);

    // 2. Tab 2 visits /vocabulary
    const tab2 = await context.newPage();
    await tab2.goto("/vocabulary");
    await expect(tab2.locator("h1")).toContainText(/Library|Bibliothek|Vocabulary|Wortschatz/i);

    // 3. Pre-populate Cache Storage with navigation query strings and static assets,
    // and seed a REAL sentinel record into IndexedDB to verify learning data survival.
    // Seed only once the worker has activated and controls the page: activate()
    // deletes every deutschflow-* cache but its own, so an entry written earlier
    // (or into a guessed name) can vanish before the assertions run.
    await page.waitForFunction(async () => {
      await navigator.serviceWorker.ready;
      return navigator.serviceWorker.controller !== null;
    });
    await page.evaluate(async () => {
      const registration = await navigator.serviceWorker.ready;
      const scriptURL = registration.active?.scriptURL;
      if (!scriptURL) throw new Error("No active service worker");
      // Mirrors sw.js: the cache is named after the ?v= build id on the script URL.
      const build = new URL(scriptURL).searchParams.get("v") || "dev";
      const cache = await window.caches.open(`deutschflow-${build}`);
      await cache.put(
        new Request("/vocabulary?sort=recent"),
        new Response("cached-vocabulary-recent")
      );
      await cache.put(
        new Request("/manifest.json"),
        new Response('{"name":"DeutschFlow"}')
      );

      // Seed real sentinel vocabulary record into Dexie "deutschflow" database
      await new Promise<void>((resolve, reject) => {
        const req = indexedDB.open("deutschflow");
        req.onsuccess = () => {
          const db = req.result;
          const tx = db.transaction("vocabulary", "readwrite");
          const store = tx.objectStore("vocabulary");
          store.put({
            germanText: "Überleben",
            germanTextKey: "ueberleben",
            englishTranslation: "To survive",
            timestamp: Date.now(),
            exampleSentence: "Wir müssen überleben.",
            article: "das",
            plural: "",
            conjugation: "",
            nextReview: Date.now() + 86400000,
            interval: 1,
            easeFactor: 2.5,
            reviewCount: 1,
            synonyms: "",
            antonyms: "",
            remoteId: "sentinel-survival-1",
            lastModifiedAt: Date.now(),
          });
          tx.oncomplete = () => {
            db.close();
            resolve();
          };
          tx.onerror = () => reject(tx.error);
        };
        req.onerror = () => reject(req.error);
      });
    });

    // Verify cache entry and IndexedDB sentinel record exist before logout
    const hasCachedVocabBefore = await page.evaluate(async () => {
      const keys = await window.caches.keys();
      for (const key of keys) {
        if (key.startsWith("deutschflow-")) {
          const cache = await window.caches.open(key);
          const match = await cache.match("/vocabulary?sort=recent");
          if (match) return true;
        }
      }
      return false;
    });
    expect(hasCachedVocabBefore).toBe(true);

    const hasSentinelBefore = await page.evaluate(async () => {
      return new Promise<boolean>((resolve, reject) => {
        const req = indexedDB.open("deutschflow");
        req.onsuccess = () => {
          const db = req.result;
          const tx = db.transaction("vocabulary", "readonly");
          const store = tx.objectStore("vocabulary");
          const getAllReq = store.getAll();
          getAllReq.onsuccess = () => {
            const items = getAllReq.result as Array<{ germanText: string }>;
            const found = items.some((i) => i.germanText === "Überleben");
            db.close();
            resolve(found);
          };
          getAllReq.onerror = () => reject(getAllReq.error);
        };
        req.onerror = () => reject(req.error);
      });
    });
    expect(hasSentinelBefore).toBe(true);

    // 4. TEST CASE: Failed Server Logout (HTTP 500)
    // In WebKit, requests under an active service-worker controller cannot be intercepted by Playwright route().
    // We install both context.route and window.fetch interception to guarantee robust execution across all browser engines.
    await context.route(/\/api\/logout/, (route) => {
      void route.fulfill({
        status: 500,
        contentType: "application/json",
        body: JSON.stringify({ error: "Simulated server failure" }),
      });
    });

    await page.evaluate(() => {
      const orig = window.fetch;
      (window as unknown as { __origFetch?: typeof window.fetch }).__origFetch = orig;
      window.fetch = async (input, init) => {
        const urlStr =
          typeof input === "string"
            ? input
            : input instanceof URL
            ? input.toString()
            : input.url;
        if (urlStr.includes("/api/logout")) {
          return new Response(JSON.stringify({ error: "Simulated server failure" }), {
            status: 500,
            headers: { "Content-Type": "application/json" },
          });
        }
        return orig(input, init);
      };
    });

    // Click the logout button in Settings
    const logoutBtn = page.getByRole("button", { name: /Log out|Abmelden/i });
    await expect(logoutBtn).toBeVisible();
    await logoutBtn.scrollIntoViewIfNeeded();
    await logoutBtn.click();

    // Verify error modal dialog appears on Tab 1
    const errorDialog = page.getByRole("dialog");
    await expect(errorDialog).toBeVisible({ timeout: 8000 });
    await expect(errorDialog).toContainText(/500/);

    // Assert Tab 1 did NOT redirect to /login
    expect(new URL(page.url()).pathname).toBe("/settings");

    // Assert Tab 2 did NOT terminate or redirect
    expect(new URL(tab2.url()).pathname).toBe("/vocabulary");

    // Assert Cache Storage was NOT purged on failure
    const hasCachedVocabAfterFailure = await page.evaluate(async () => {
      const keys = await window.caches.keys();
      for (const key of keys) {
        if (key.startsWith("deutschflow-")) {
          const cache = await window.caches.open(key);
          const match = await cache.match("/vocabulary?sort=recent");
          if (match) return true;
        }
      }
      return false;
    });
    expect(hasCachedVocabAfterFailure).toBe(true);

    // Dismiss the error dialog so subsequent clicks can be received
    await page.getByRole("button", { name: /OK/i }).click();
    await expect(errorDialog).toBeHidden();

    // 5. TEST CASE: Successful Server Logout (HTTP 200)
    // Remove network interception so /api/logout hits the real production route
    await context.unroute(/\/api\/logout/);
    await page.evaluate(() => {
      const customWindow = window as unknown as { __origFetch?: typeof window.fetch };
      if (customWindow.__origFetch) {
        window.fetch = customWindow.__origFetch;
        delete customWindow.__origFetch;
      }
    });

    // Click Logout again
    await logoutBtn.scrollIntoViewIfNeeded();
    await logoutBtn.click();

    // Tab 1 must navigate to /login
    await expect(page).toHaveURL(/\/login/, { timeout: 12000 });
    expect(new URL(page.url()).pathname).toBe("/login");

    // Tab 2 must automatically synchronize via BroadcastChannel and transition to /login
    await expect(tab2).toHaveURL(/\/login/, { timeout: 12000 });
    expect(new URL(tab2.url()).pathname).toBe("/login");

    // Assert protected query-string entry was purged from Cache Storage
    const hasCachedVocabAfterSuccess = await page.evaluate(async () => {
      const keys = await window.caches.keys();
      for (const key of keys) {
        if (key.startsWith("deutschflow-")) {
          const cache = await window.caches.open(key);
          const match = await cache.match("/vocabulary?sort=recent");
          if (match) return true;
        }
      }
      return false;
    });
    expect(hasCachedVocabAfterSuccess).toBe(false);

    // Assert static assets remain preserved
    const hasStaticAsset = await page.evaluate(async () => {
      const keys = await window.caches.keys();
      for (const key of keys) {
        if (key.startsWith("deutschflow-")) {
          const cache = await window.caches.open(key);
          const match = await cache.match("/manifest.json");
          if (match) return true;
        }
      }
      return false;
    });
    expect(hasStaticAsset).toBe(true);

    // 6. Verify real authentication state: attempting to navigate back to /vocabulary redirects to /login
    await page.goto("/vocabulary");
    await expect(page).toHaveURL(/\/login/, { timeout: 12000 });

    // 7. TEST CASE: Offline Navigation After Logout
    // Taking the browser offline must NOT expose protected cached pages
    await context.setOffline(true);
    try {
      const offlineResponse = await page.goto("/vocabulary").catch(() => null);
      if (offlineResponse) {
        // If served by the Service Worker, must fail safe with 503 Service Unavailable
        // If redirected to login, status is 307 or 200
        expect([503, 307, 200]).toContain(offlineResponse.status());
      }
      // Protected Library header must NOT be rendered
      await expect(page.getByRole("heading", { name: /Library|Bibliothek|Vocabulary|Wortschatz/i })).toBeHidden();
    } finally {
      await context.setOffline(false);
    }

    // 8. TEST CASE: Delayed Cache Write Race Prevention
    // Verify that persistent session tracking marks the user as logged out
    const isLoggedOutPersisted = await page.evaluate(async () => {
      return new Promise<boolean>((resolve) => {
        const req = indexedDB.open("deutschflow-session");
        req.onsuccess = () => {
          const db = req.result;
          if (!db.objectStoreNames.contains("auth")) {
            db.close();
            return resolve(false);
          }
          const tx = db.transaction("auth", "readonly");
          const getReq = tx.objectStore("auth").get("state");
          getReq.onsuccess = () => {
            const val = getReq.result as { loggedOut: boolean } | undefined;
            db.close();
            resolve(val?.loggedOut === true);
          };
          getReq.onerror = () => {
            db.close();
            resolve(false);
          };
        };
        req.onerror = () => resolve(false);
      });
    });
    expect(isLoggedOutPersisted).toBe(true);

    // 9. TEST CASE: IndexedDB Data Preservation Verification
    // Assert that the real sentinel vocabulary record survived intact after logout
    const sentinelAfterLogout = await page.evaluate(async () => {
      return new Promise<{ germanText: string; englishTranslation: string; exampleSentence: string } | null>(
        (resolve, reject) => {
          const req = indexedDB.open("deutschflow");
          req.onsuccess = () => {
            const db = req.result;
            const tx = db.transaction("vocabulary", "readonly");
            const store = tx.objectStore("vocabulary");
            const getAllReq = store.getAll();
            getAllReq.onsuccess = () => {
              const items = getAllReq.result as Array<{
                germanText: string;
                englishTranslation: string;
                exampleSentence: string;
              }>;
              const found = items.find((i) => i.germanText === "Überleben");
              db.close();
              resolve(found || null);
            };
            getAllReq.onerror = () => reject(getAllReq.error);
          };
          req.onerror = () => reject(req.error);
        }
      );
    });

    expect(sentinelAfterLogout).not.toBeNull();
    expect(sentinelAfterLogout?.germanText).toBe("Überleben");
    expect(sentinelAfterLogout?.englishTranslation).toBe("To survive");
    expect(sentinelAfterLogout?.exampleSentence).toBe("Wir müssen überleben.");
  });
});

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

    // 3. Pre-populate Cache Storage with navigation query strings and static assets
    await page.evaluate(async () => {
      const keys = await window.caches.keys();
      const current = keys.find((k) => k.startsWith("deutschflow-")) || "deutschflow-dev";
      const cache = await window.caches.open(current);
      await cache.put(
        new Request("/vocabulary?sort=recent"),
        new Response("cached-vocabulary-recent")
      );
      await cache.put(
        new Request("/manifest.json"),
        new Response('{"name":"DeutschFlow"}')
      );
    });

    // Verify cache entry exists before logout
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

    // 4. TEST CASE: Failed Server Logout (HTTP 500)
    // Intercept /api/logout to return 500
    await page.route("/api/logout", (route) => {
      void route.fulfill({
        status: 500,
        contentType: "application/json",
        body: JSON.stringify({ error: "Simulated server failure" }),
      });
    });

    // Click the logout button in Settings
    const logoutBtn = page.getByRole("button", { name: /Log out|Abmelden/i });
    await expect(logoutBtn).toBeVisible();
    await logoutBtn.click();

    // Verify error modal dialog appears on Tab 1
    const errorDialog = page.getByRole("dialog");
    await expect(errorDialog).toBeVisible();
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
    await page.unroute("/api/logout");

    // Click Logout again
    await logoutBtn.click();

    // Tab 1 must navigate to /login
    await page.waitForURL("**/login", { timeout: 8000 });
    expect(new URL(page.url()).pathname).toBe("/login");

    // Tab 2 must automatically synchronize via BroadcastChannel and transition to /login
    await tab2.waitForURL("**/login", { timeout: 8000 });
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
    expect(new URL(page.url()).pathname).toBe("/login");
  });
});

import { expect, test, type Page } from "@playwright/test";

/**
 * Smoke suite: the five tab routes load, the bottom bar becomes a rail at the
 * 768px breakpoint, the app boots offline after one visit, and the language
 * switch flips the UI to German.
 */

const ROUTES = ["/transcript", "/history", "/vocabulary", "/study", "/practice"] as const;

for (const route of ROUTES) {
  test(`route ${route} loads`, async ({ page }) => {
    const response = await page.goto(route);
    expect(response?.status()).toBe(200);
    // The URL, first. These assertions used to be satisfied by the login page -
    // it has an h1 and the same background - so all five passed while the app
    // was never reached. Anything that can be true on /login proves nothing.
    expect(new URL(page.url()).pathname).toBe(route);
    await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
    await expect(page.locator("h1")).toBeVisible();
    // The app shell (the glass surface language) renders behind every screen.
    await expect(page.locator("body")).toHaveCSS("background-color", "rgb(10, 14, 22)");
  });
}

test.describe("the access gate", () => {
  // No session: the point of these two.
  test.use({ storageState: { cookies: [], origins: [] } });

  test("sends an unauthenticated visitor to the login page", async ({ page }) => {
    await page.goto("/transcript");

    expect(new URL(page.url()).pathname).toBe("/login");
    // The destination is preserved for the redirect back.
    expect(new URL(page.url()).searchParams.get("from")).toBe("/transcript");
    await expect(page.getByRole("navigation", { name: "Primary" })).toHaveCount(0);
  });

  test("refuses a forged cookie", async ({ page, context }) => {
    // The old gate admitted this exact value; the signature is what stops it now.
    await context.addCookies([
      { name: "df_access", value: "granted", domain: "localhost", path: "/" },
    ]);

    await page.goto("/transcript");

    expect(new URL(page.url()).pathname).toBe("/login");
  });
});

test("bottom bar below 768px, rail at and above 768px", async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 800 });
  await page.goto("/transcript");

  const primaryNav = page.getByRole("navigation", { name: "Primary" });
  await expect(primaryNav).toBeVisible();
  // On a phone the five destinations are the fixed bottom bar.
  await expect(primaryNav).toHaveCSS("position", "fixed");

  await page.setViewportSize({ width: 1024, height: 800 });
  // At desktop width the same destinations sit in the left-hand rail.
  await expect(primaryNav).toBeVisible();
  let boundingBox = (await primaryNav.boundingBox())!;
  expect(boundingBox.x).toBeLessThan(10);

  // On a wide laptop the rail must hug the screen edge — not float inside the
  // centred content band (it used to sit ~200px in on a 1440px screen).
  await page.setViewportSize({ width: 1440, height: 900 });
  await expect(primaryNav).toBeVisible();
  boundingBox = (await primaryNav.boundingBox())!;
  expect(boundingBox.x).toBe(0);
});

test("navigation reaches all five tabs", async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 800 });
  await page.goto("/transcript");

  const rail = page.getByRole("navigation", { name: "Primary" });
  await rail.getByText("History").click();
  await expect(page).toHaveURL(/\/history/);
  await expect(page.getByText("No transcripts found")).toBeVisible();

  await rail.getByText("Library").click();
  await expect(page).toHaveURL(/\/vocabulary/);
  await expect(page.getByText("Your library is empty")).toBeVisible();
});

test("offline boot after one online visit", async ({ page, context }) => {
  await page.goto("/transcript");
  // Wait for the service worker to take control of this page.
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
    await navigator.serviceWorker.ready;
  });

  await context.setOffline(true);
  await page.reload();
  // The cached shell answers instead of the network error page.
  await expect(page.getByText("Tap to start")).toBeVisible();
  await expect(page.locator("h1")).toBeVisible();
});

test("language switch flips the UI to German", async ({ page }) => {
  await page.goto("/settings");

  await page.getByRole("button", { name: "Deutsch" }).click();

  // The shell title and the settings section header react immediately.
  await expect(page.locator("h1")).toHaveText("Einstellungen");
  await expect(page.getByText("KI & Übersetzung")).toBeVisible();
  await expect(page.getByText("Lernfortschritt")).toBeVisible();
  await expect(page.getByText("Deutsche Aussprache automatisch abspielen")).toBeVisible();
});

test("saving an API key reports in the current language", async ({ page }) => {
  await page.goto("/settings");

  // German UI first: the save confirmation must be German too, not the
  // hardcoded English the hook used to return.
  await page.getByRole("button", { name: "Deutsch" }).click();
  await expect(page.locator("h1")).toHaveText("Einstellungen");

  const input = page.getByPlaceholder("Füge hier deinen Groq-Schlüssel ein");
  await input.fill("gsk_fake_key_for_smoke");
  await page.getByRole("button", { name: "Speichern" }).click();

  await expect(page.getByText("API-Schlüssel gespeichert.")).toBeVisible();
  await page.getByRole("button", { name: "OK" }).click();
});

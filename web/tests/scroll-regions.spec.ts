import { test, expect, type Page } from "@playwright/test";

/**
 * Long content must be reachable with the mouse wheel.
 *
 * The shell is a fixed-height viewport that owns no scrolling of its own, so each
 * screen's scroll region only works if every ancestor hands it a bounded height.
 * One implicit `auto` grid row in the shell broke that: on a 1440x900 desktop a
 * 30-word library put its last row at y=3241 and nothing the wheel could move.
 */

async function seedLibrary(page: Page, count: number) {
  await page.goto("/settings");
  const now = Date.now();
  const vocabulary = Array.from({ length: count }, (_, i) => ({
    germanText: `Wort${i}`, englishTranslation: `word ${i}`, exampleSentence: "", article: "", plural: "",
    conjugation: "", synonyms: "", antonyms: "", timestamp: now - i * 1000, nextReview: 0, interval: 0,
    reviewCount: 0, easeFactor: 2.5, remoteId: `scroll-${i}`, lastModifiedAt: now,
  }));
  await page.locator('input[type="file"]').setInputFiles({
    name: "library.json",
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify({
      format: "deutschflow-library", version: 2, exportedAt: new Date(now).toISOString(),
      vocabulary, transcripts: [], userStats: [], activityLog: [], reviewEvents: [], roleplayMessages: [],
    })),
  });
  await expect(page.getByRole("dialog")).toContainText(/import completed|restored/i);
  await page.getByRole("dialog").getByRole("button", { name: "OK", exact: true }).click();
}

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  test(`a long library scrolls with the wheel at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await seedLibrary(page, 30);
    await page.goto("/vocabulary");
    const last = page.getByText("Wort29", { exact: true });
    await expect(last).toBeAttached();
    await expect(last).not.toBeInViewport();

    await page.mouse.move(viewport.width / 2, viewport.height / 2);
    await expect(async () => {
      await page.mouse.wheel(0, 2000);
      await expect(last).toBeInViewport({ timeout: 500 });
    }).toPass({ timeout: 10_000 });
  });
}

test("the desktop word details stay in view for a word far down the list", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await seedLibrary(page, 30);
  await page.goto("/vocabulary");
  await page.getByText("Wort29", { exact: true }).click();
  await expect(page.getByRole("heading", { name: "Wort29", level: 2 })).toBeInViewport();
});

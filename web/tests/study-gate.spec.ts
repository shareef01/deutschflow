import { expect, test, type Page } from "@playwright/test";

/**
 * Flashcard interaction regressions.
 *
 * One test per defect. Each drives the app the way a learner does, so the fix
 * cannot quietly regress behind a passing type-check.
 */

/** Puts one word in the library, through the UI, so the study deck has a card. */
async function seedWord(page: Page, german: string, english: string) {
  await page.goto("/vocabulary");
  await page.getByRole("button", { name: /add a word/i }).click();
  await page.getByLabel("German", { exact: true }).fill(german);
  await page.getByLabel("Translation", { exact: true }).fill(english);
  await page.getByRole("button", { name: /add to library/i }).click();
  await expect(page.getByText(german, { exact: true }).first()).toBeVisible();
}

async function openStudy(page: Page) {
  await page.goto("/study");
  await page.getByRole("tab", { name: /flashcards/i }).click();
  await expect(page.getByRole("button", { name: /show the translation/i })).toBeVisible();
}

test("grades are inert until the answer is revealed", async ({ page }) => {
  await seedWord(page, "die Katze", "the cat");
  await openStudy(page);

  // The card has not been turned over, so there is nothing to judge yet.
  const again = page.getByRole("button", { name: /^again/i });
  const easy = page.getByRole("button", { name: /^easy/i });
  await expect(again).toBeDisabled();
  await expect(easy).toBeDisabled();
  await expect(page.getByText("Show the answer to grade this card")).toBeVisible();

  await page.getByRole("button", { name: /show the translation/i }).click();
  await expect(again).toBeEnabled();
  await expect(easy).toBeEnabled();
});

test("a grading shortcut does nothing before the reveal", async ({ page }) => {
  await seedWord(page, "der Hund", "the dog");
  await openStudy(page);

  // Nothing focused: the shortcut is allowed to act, but there is nothing to grade.
  await page.locator("body").click({ position: { x: 5, y: 5 } });
  await page.keyboard.press("1");

  // Still on the first card, still unrevealed — revealing is not reviewing and
  // reviewing is not possible yet.
  await expect(page.getByRole("button", { name: /^again/i })).toBeDisabled();
  await expect(page.getByRole("button", { name: /show the translation/i })).toBeVisible();
});

test("Space reveals the card without grading it", async ({ page }) => {
  await seedWord(page, "das Buch", "the book");
  await openStudy(page);

  await page.locator("body").click({ position: { x: 5, y: 5 } });
  await page.keyboard.press("Space");
  await expect(page.getByRole("button", { name: /show the german/i })).toBeVisible();
  // One card, still one card: revealing is not reviewing.
  await expect(page.getByText("1 of 1")).toBeVisible();
});

test("Space on a focused grade button does not flip instead of grading", async ({ page }) => {
  await seedWord(page, "der Baum", "the tree");
  await openStudy(page);
  await page.getByRole("button", { name: /show the translation/i }).click();

  const good = page.getByRole("button", { name: /^good/i });
  await good.focus();
  // The focused control owns Space. The screen-wide shortcut stands down, so the
  // key activates the button the user chose rather than flipping the card back.
  await page.keyboard.press("Space");
  await expect(page.getByRole("button", { name: /show the translation/i })).toHaveCount(0);
});

test("a repeated grading key submits one review, not two", async ({ page }) => {
  await seedWord(page, "die Maus", "the mouse");
  await seedWord(page, "der Tisch", "the table");
  await openStudy(page);
  await page.getByRole("button", { name: /show the translation/i }).click();

  await page.keyboard.press("3");
  await page.keyboard.press("3");

  // One review only: a second card remains, and it is unrevealed.
  await expect(page.getByText(/session complete/i)).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^good/i })).toBeDisabled();
  // And the stale-snapshot path was never taken, so no "this card changed
  // elsewhere" notice for what was one keystroke too many.
  await expect(page.getByText(/changed elsewhere/i)).toHaveCount(0);
});

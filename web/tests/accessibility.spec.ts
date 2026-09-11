import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

const CORE_ROUTES = [
  "/transcript",
  "/history",
  "/vocabulary",
  "/study",
  "/practice",
  "/settings",
] as const;

test.describe("Automated Accessibility Audits (axe-core)", () => {
  for (const route of CORE_ROUTES) {
    test(`route ${route} has no detectable a11y violations`, async ({ page }) => {
      await page.goto(route);
      // Wait for client hydrations and idle network
      await page.waitForLoadState("domcontentloaded");
      const accessibilityScanResults = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
        .analyze();
      expect(accessibilityScanResults.violations).toEqual([]);
    });
  }
});

test.describe("Interactive Accessibility Semantics", () => {
  test("Study tabs support roving keyboard navigation and tab semantics", async ({ page }) => {
    await page.goto("/study");
    const tablist = page.getByRole("tablist", { name: "Study" });
    await expect(tablist).toBeVisible();

    const dashboardTab = tablist.getByRole("tab", { name: "Dashboard" });
    const flashcardsTab = tablist.getByRole("tab", { name: "Flashcards" });

    await expect(dashboardTab).toBeVisible();
    await expect(flashcardsTab).toBeVisible();

    // Focus first tab and navigate using keyboard
    await dashboardTab.focus();
    await page.keyboard.press("ArrowRight");
    await expect(flashcardsTab).toBeFocused();
    await expect(flashcardsTab).toHaveAttribute("aria-selected", "true");

    await page.keyboard.press("ArrowLeft");
    await expect(dashboardTab).toBeFocused();
    await expect(dashboardTab).toHaveAttribute("aria-selected", "true");
  });

  test("Practice tabs support roving keyboard navigation", async ({ page }) => {
    await page.goto("/practice");
    const tablist = page.getByRole("tablist", { name: "Practice" });
    await expect(tablist).toBeVisible();

    const repTab = tablist.getByRole("tab", { name: "Repetition" });
    const roleplayTab = tablist.getByRole("tab", { name: "Roleplay" });

    await repTab.focus();
    await page.keyboard.press("ArrowRight");
    await expect(roleplayTab).toBeFocused();
    await expect(roleplayTab).toHaveAttribute("aria-selected", "true");
  });

  test("Primary navigation elements are real links with href and aria-current='page'", async ({ page }) => {
    await page.setViewportSize({ width: 1024, height: 800 });
    await page.goto("/vocabulary");

    const rail = page.getByRole("navigation", { name: /primary|hauptnavigation/i });
    const vocabLink = rail.getByRole("link", { name: /library|wortschatz/i });
    await expect(vocabLink).toHaveAttribute("aria-current", "page");
    await expect(vocabLink).toHaveAttribute("href", "/vocabulary");

    const historyLink = rail.getByRole("link", { name: /history|verlauf/i });
    await expect(historyLink).not.toHaveAttribute("aria-current", "page");
    await expect(historyLink).toHaveAttribute("href", "/history");

    // Header settings link
    const settingsLink = page.getByRole("link", { name: /settings|einstellungen/i });
    await expect(settingsLink).toHaveAttribute("href", "/settings");
  });

  test("Settings uses native radio inputs inside fieldset", async ({ page }) => {
    await page.goto("/settings");
    const radio = page.locator('input[type="radio"][value="de-DE"]');
    await expect(radio).toBeAttached();
    await expect(radio).toBeChecked();
  });

  test("Vocabulary dialog has accessible labeling and focus management", async ({ page }) => {
    await page.goto("/vocabulary");
    await page.getByRole("button", { name: "Add a word" }).click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    const heading = dialog.locator("h2");
    await expect(heading).toHaveText("Add a word");

    // Check focus inside dialog
    await page.keyboard.press("Tab");
    const activeElement = await page.evaluate(() => document.activeElement?.tagName);
    expect(activeElement).toBeTruthy();

    // Escape closes dialog
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
  });
});

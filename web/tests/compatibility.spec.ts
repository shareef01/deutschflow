import { expect, test } from "@playwright/test";

/** Small cross-engine contract; Chromium carries the full functional suite. */
test("authenticated app shell loads and navigation works", async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 800 });
  const response = await page.goto("/transcript");
  await page.waitForLoadState("networkidle");

  expect(response?.status()).toBe(200);
  expect(new URL(page.url()).pathname).toBe("/transcript");
  const navigation = page.getByRole("navigation", { name: "Primary" });
  await expect(navigation).toBeVisible();
  await expect(page.locator("h1")).toBeVisible();

  await navigation.getByRole("link", { name: /library/i }).click();
  await expect(page).toHaveURL(/\/vocabulary$/);
  await expect(page.getByText("Your library is empty")).toBeVisible();
});

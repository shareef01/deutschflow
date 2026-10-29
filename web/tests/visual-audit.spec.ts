import { test, expect } from "@playwright/test";

const VIEWPORTS = [
  { name: "mobile-360", width: 360, height: 740 },
  { name: "mobile-390", width: 390, height: 844 },
  { name: "tablet-768", width: 768, height: 1024 },
  { name: "desktop-1440", width: 1440, height: 900 },
];

const ROUTES = [
  { path: "/transcript", name: "transcript" },
  { path: "/history", name: "history" },
  { path: "/vocabulary", name: "vocabulary" },
  { path: "/study", name: "study" },
  { path: "/practice", name: "practice" },
  { path: "/settings", name: "settings" },
];

test.describe("visual regression", () => {
  for (const vp of VIEWPORTS) {
    for (const route of ROUTES) {
      test(`${route.name} at ${vp.name}`, async ({ page }) => {
        await page.setViewportSize({ width: vp.width, height: vp.height });
        const response = await page.goto(route.path);
        expect(response?.status()).toBe(200);
        await page.waitForLoadState("networkidle");
        expect(new URL(page.url()).pathname).toBe(route.path);
        if (route.path === "/settings") {
          await expect(page.getByRole("button", { name: "Back", exact: true })).toBeVisible();
        } else {
          await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
        }
        await expect(page.locator("h1")).toBeVisible();
        await expect(page.getByText(/application error|internal server error/i)).toHaveCount(0);

        await expect(page).toHaveScreenshot(`${route.name}-${vp.name}.png`, {
          animations: "disabled",
          caret: "hide",
          // 1%, not the 8% this shipped with. At 8% a 390×844 image could lose
          // ~26,000 pixels — a whole navigation bar — and still pass, which is how a
          // real layout change lands with the suite green and the committed
          // baselines stale. The structural assertions above are the semantic net;
          // this budget is only for antialiasing and font-hinting noise.
          maxDiffPixelRatio: 0.01,
        });
      });
    }
  }
});

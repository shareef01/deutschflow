import { test, expect } from "@playwright/test";
import path from "path";

const VIEWPORTS = [
  { name: "mobile_360", width: 360, height: 740 },
  { name: "mobile_390", width: 390, height: 844 },
  { name: "tablet_768", width: 768, height: 1024 },
  { name: "desktop_1440", width: 1440, height: 900 },
];

const ROUTES = [
  { path: "/transcript", name: "transcript" },
  { path: "/history", name: "history" },
  { path: "/vocabulary", name: "vocabulary" },
  { path: "/study", name: "study" },
  { path: "/practice", name: "practice" },
  { path: "/settings", name: "settings" },
];

test.describe("Visual Audit Screenshots", () => {
  for (const vp of VIEWPORTS) {
    for (const route of ROUTES) {
      test(`capture ${route.name} at ${vp.name}`, async ({ page }) => {
        await page.setViewportSize({ width: vp.width, height: vp.height });
        await page.goto(route.path);
        await page.waitForLoadState("networkidle");
        await page.waitForTimeout(300);
        
        const screenshotPath = path.resolve(
          process.cwd(),
          `../docs/screenshots/web/${route.name}_${vp.name}.png`
        );
        await page.screenshot({ path: screenshotPath, fullPage: false });
        expect(true).toBe(true);
      });
    }
  }
});

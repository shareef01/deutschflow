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

/** The primary destinations, in order. Asserted structurally, see below. */
const PRIMARY_HREFS = ["/transcript", "/history", "/vocabulary", "/study", "/practice"];

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
          const nav = page.getByRole("navigation", { name: "Primary" });
          await expect(nav).toBeVisible();

          // The navigation model, asserted as structure rather than as pixels.
          //
          // A pixel budget cannot be the guard for this: a diff measured across
          // environments costs ~2% on its own (different font rasterisation in the
          // Playwright container than on a developer's machine), which is why this
          // once tolerated 8% — and 8% is ~26,000 pixels on a 390×844 image, enough
          // for a destination to appear or disappear unnoticed. These assertions
          // fail on that change immediately, at any budget.
          const links = nav.getByRole("link");
          await expect(links).toHaveCount(PRIMARY_HREFS.length);
          const hrefs = await links.evaluateAll((nodes) =>
            nodes.map((node) => node.getAttribute("href"))
          );
          expect(hrefs).toEqual(PRIMARY_HREFS);

          // Exactly one destination is current, and it is this page's.
          await expect(nav.locator('[aria-current="page"]')).toHaveCount(1);
          if (PRIMARY_HREFS.includes(route.path)) {
            await expect(nav.locator(`a[href="${route.path}"]`)).toHaveAttribute(
              "aria-current",
              "page"
            );
          }
        }
        await expect(page.locator("h1")).toBeVisible();
        await expect(page.getByText(/application error|internal server error/i)).toHaveCount(0);

        // Pixel baselines are a **local design-review tool**, not a CI gate.
        //
        // Measured on this change: the same code rendered on a developer's machine
        // and in CI differs by 2–4% from font rasterisation alone, because CI runs
        // the browser in Playwright's own Linux image. No budget tight enough to
        // catch a real regression is loose enough to survive that, which is how this
        // spec ended up tolerating 8% — and 8% is ~26,000 px on a 390×844 image,
        // enough for a whole navigation bar to change unnoticed.
        //
        // So the semantic assertions above run everywhere, and the pixels are
        // compared only where the committed baselines were produced. Regenerate them
        // with `npx playwright test -g "visual regression" --update-snapshots=changed`
        // after *looking* at every diff. To gate pixels in CI, the baselines have to
        // be generated in the same image CI uses:
        //   docker run --rm -v "$PWD/..":/w -w /w/web mcr.microsoft.com/playwright:v1.64.0-jammy \
        //     npx playwright test -g "visual regression" --update-snapshots=all
        if (!process.env.CI) {
          await expect(page).toHaveScreenshot(`${route.name}-${vp.name}.png`, {
            animations: "disabled",
            caret: "hide",
            maxDiffPixelRatio: 0.03,
          });
        }
      });
    }
  }
});

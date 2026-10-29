import { expect, test } from "@playwright/test";

/**
 * Responsive and preference coverage.
 *
 * The visual baselines capture four widths; this checks the two properties a
 * screenshot cannot: that nothing overflows the viewport at any of the widths
 * the product supports, and that the two user preferences the app claims to
 * honour — reduced motion, and enlarged text — are actually honoured.
 */

const WIDTHS = [
  { name: "320", width: 320, height: 640 },
  { name: "360", width: 360, height: 740 },
  { name: "390", width: 390, height: 844 },
  { name: "768", width: 768, height: 1024 },
  { name: "1024", width: 1024, height: 800 },
  { name: "1440", width: 1440, height: 900 },
  { name: "1920", width: 1920, height: 1080 },
] as const;

const ROUTES = ["/transcript", "/history", "/vocabulary", "/study", "/practice", "/settings"] as const;

test.describe("no horizontal overflow", () => {
  for (const width of WIDTHS) {
    for (const route of ROUTES) {
      test(`${route} at ${width.name}px`, async ({ page }) => {
        await page.setViewportSize({ width: width.width, height: width.height });
        await page.goto(route);
        await page.waitForLoadState("domcontentloaded");

        const overflow = await page.evaluate(() => {
          const el = document.documentElement;
          return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth };
        });
        // One pixel of slack for sub-pixel rounding on fractional widths.
        expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1);

        // And the page reached the app rather than a gate or an error page.
        await expect(page.locator("h1")).toBeVisible();
      });
    }
  }
});

test("reduced motion actually shortens the app's transitions", async ({ page }) => {
  // Measured both ways round: without the preference the same element must
  // animate, or the assertion below would pass on a page with no transitions at
  // all and prove nothing.
  const durationOf = async () =>
    page
      .locator("header a")
      .first()
      .evaluate((el) =>
        getComputedStyle(el)
          .transitionDuration.split(",")
          .map((part) => Number.parseFloat(part.trim()))
          .reduce((max, value) => Math.max(max, value), 0)
      );

  await page.goto("/transcript");
  const animated = await durationOf();
  expect(animated).toBeGreaterThan(0.1);

  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.reload();
  const reduced = await durationOf();
  expect(reduced).toBeLessThan(0.05);
});

test("the layout still fits at 200% text", async ({ page }) => {
  // WCAG 1.4.4 (Resize text) is about the browser's text size, which is the root
  // font size. Doubling it is the closest a browser automation can get to a user
  // who has done exactly that in their settings.
  await page.setViewportSize({ width: 320, height: 640 });
  for (const route of ROUTES) {
    await page.goto(route);
    await page.addStyleTag({ content: "html { font-size: 200% !important; }" });
    await page.waitForLoadState("domcontentloaded");

    const overflow = await page.evaluate(() => {
      const el = document.documentElement;
      return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth };
    });
    expect(`${route}:${overflow.scrollWidth <= overflow.clientWidth + 1}`).toBe(`${route}:true`);
  }
});

test("long German words wrap instead of overflowing their card", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto("/vocabulary");
  await page.getByRole("button", { name: "Add a word" }).click();

  const longWord = "die Donaudampfschifffahrtsgesellschaftskapitaenskajuetenschluesselloch";
  await page.getByLabel("German").fill(longWord);
  await page.getByLabel("Translation").fill("the Danube steamship company captain's cabin keyhole");
  await page.getByRole("button", { name: "Add to library" }).click();

  const row = page.locator("li", { hasText: "Donaudampf" }).first();
  await expect(row).toBeVisible();

  const box = (await row.boundingBox())!;
  expect(box.width).toBeLessThanOrEqual(320);

  const overflow = await page.evaluate(() => {
    const el = document.documentElement;
    return el.scrollWidth - el.clientWidth;
  });
  expect(overflow).toBeLessThanOrEqual(1);
});

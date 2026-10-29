import { expect, test, type Page } from "@playwright/test";

/**
 * Roleplay scrolling, end to end.
 *
 * The defect this pins down: the conversation used to scroll itself to the
 * bottom whenever the message count, the pending state *or* the live transcript
 * changed — so re-reading an earlier exchange while the microphone was open was
 * impossible, because the list moved several times a second, mid-word.
 *
 * The unit suite covers the decision (`tests/scroll-follow.test.ts`); this
 * covers the wiring, which is where the bug actually lived.
 */

/**
 * A reply long enough to fill (and overflow) a phone-sized viewport while staying
 * under `MAX_ROLEPLAY_REPLY_CHARS` (1000) — replies are capped by the AI layer, so
 * a longer mock would be truncated and the tail would never exist to assert on.
 */
const LONG_REPLY =
  "Response: " +
  "Antwort auf Deutsch. ".repeat(46) +
  "\nContext: A deliberately long reply so the log has to scroll.";

async function configureKey(page: Page) {
  await page.goto("/settings");
  await page.getByLabel("Groq API key", { exact: true }).fill("audit-scroll-key");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "OK", exact: true }).click();
}

async function openRoleplay(page: Page) {
  await page.route("https://api.groq.com/**", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ choices: [{ message: { content: LONG_REPLY } }] }),
    })
  );
  await configureKey(page);
  await page.addInitScript(() => {
    delete (window as unknown as Record<string, unknown>).SpeechRecognition;
    delete (window as unknown as Record<string, unknown>).webkitSpeechRecognition;
  });
  await page.goto("/practice");
  await page.getByRole("tab", { name: "Roleplay" }).click();
}

const geometry = (page: Page) =>
  page.getByRole("log").evaluate((el) => ({
    scrollTop: el.scrollTop,
    scrollHeight: el.scrollHeight,
    clientHeight: el.clientHeight,
  }));

test("a reader parked at the bottom keeps following the conversation", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openRoleplay(page);

  const log = page.getByRole("log");
  await expect(log).toBeVisible();
  await expect(log.getByText(/Antwort auf Deutsch/)).toBeVisible();

  // Following the opening line means the end of it is in view. Polled, because
  // the follow is deliberately deferred to a post-layout frame.
  await expect
    .poll(async () => (await geometry(page)).scrollTop, { timeout: 10_000 })
    .toBeGreaterThan(0);
  const before = await geometry(page);
  expect(before.scrollHeight).toBeGreaterThan(before.clientHeight);

  await page.getByPlaceholder("Type your message in German…").fill("Hallo");
  await page.getByRole("button", { name: "Send", exact: true }).click();

  // The new turn pulled the viewport with it, and no jump control is offered.
  await expect
    .poll(async () => {
      const after = await geometry(page);
      return after.scrollHeight - after.scrollTop - after.clientHeight;
    })
    .toBeLessThanOrEqual(2);
  await expect(page.getByRole("button", { name: "Jump to latest" })).toHaveCount(0);
});

test("a reader re-reading earlier turns keeps their place, and can jump back", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openRoleplay(page);
  await expect(page.getByRole("log").getByText(/Antwort auf Deutsch/)).toBeVisible();

  // Scroll back to the top of the conversation.
  await page.getByRole("log").evaluate((el) => {
    el.scrollTop = 0;
  });
  await expect.poll(async () => (await geometry(page)).scrollTop).toBe(0);

  await page.getByPlaceholder("Type your message in German…").fill("Noch einmal");
  await page.getByRole("button", { name: "Send", exact: true }).click();

  // The new turn must not have dragged the viewport away from where they were.
  await expect(page.getByRole("button", { name: "Jump to latest" })).toBeVisible();
  expect((await geometry(page)).scrollTop).toBe(0);

  // And the offered control takes them to the end.
  await page.getByRole("button", { name: "Jump to latest" }).click();
  await expect
    .poll(async () => {
      const after = await geometry(page);
      return after.scrollHeight - after.scrollTop - after.clientHeight;
    })
    .toBeLessThanOrEqual(2);
  await expect(page.getByRole("button", { name: "Jump to latest" })).toHaveCount(0);
});

test("interim transcription does not move the conversation", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openRoleplay(page);
  await expect(page.getByRole("log").getByText(/Antwort auf Deutsch/)).toBeVisible();

  await page.getByRole("log").evaluate((el) => {
    el.scrollTop = 0;
  });
  await expect.poll(async () => (await geometry(page)).scrollTop).toBe(0);

  // No speech API in this context, so the mic is unavailable — which is exactly
  // the state in which the old code still scrolled: `partialText` was in the
  // effect's dependency list whether or not anything was listening.
  await expect(page.getByText(/Voice input is unavailable/i)).toBeVisible();
  await page.waitForTimeout(300);
  expect((await geometry(page)).scrollTop).toBe(0);
});

import { test, expect, type Page } from "@playwright/test";

async function configure(page: Page) {
  await page.goto("/settings");
  await page.getByLabel("Groq API key", { exact: true }).fill("audit-test-key");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("A key is saved on this device.", { exact: true })).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "OK", exact: true }).click();
}

for (const mode of ["transcript", "repetition", "roleplay"] as const) {
  test(`${mode}: late permission does not acquire the microphone after departure`, async ({ page }) => {
    await page.addInitScript(() => {
      const state = { requests: 0, starts: 0, grant: () => {} };
      Object.defineProperty(window, "__micAudit", { value: state });
      Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: {
        getUserMedia: () => {
          state.requests++;
          return new Promise(resolve => { state.grant = () => resolve({ getTracks: () => [{ stop() {} }] }); });
        },
      } });
      Object.defineProperty(window, "SpeechRecognition", { configurable: true, value: class {
        start() { state.starts++; }
        abort() {}
        stop() {}
      } });
    });
    await page.goto(mode === "transcript" ? "/transcript" : "/practice");
    if (mode === "roleplay") await page.getByRole("tab", { name: "Roleplay", exact: true }).click();
    const buttonName = mode === "transcript" ? "Start recording" : mode === "roleplay" ? "Speak to Reply" : "Speak";
    await page.getByRole("button", { name: buttonName, exact: true }).click();
    await expect.poll(() => page.evaluate(() => (window as unknown as { __micAudit: { requests: number } }).__micAudit.requests)).toBe(1);
    // Client navigation keeps the pending promise alive; a full page load would hide the bug.
    if (mode === "transcript") {
      await page.getByRole("link", { name: "Library", exact: true }).click();
      await expect(page).toHaveURL(/\/vocabulary$/);
      await expect(page.getByRole("heading", { name: "Library", exact: true })).toBeVisible();
    } else {
      const destination = page.getByRole("tab", { name: mode === "repetition" ? "Roleplay" : "Repetition", exact: true });
      await destination.click();
      await expect(destination).toHaveAttribute("aria-selected", "true");
    }
    const starts = await page.evaluate(async () => {
      const state = (window as unknown as { __micAudit: { starts: number; grant: () => void } }).__micAudit;
      state.grant();
      // Drain the permission and IndexedDB continuations, then inspect acquisition.
      await new Promise(resolve => setTimeout(resolve, 150));
      return state.starts;
    });
    expect(starts).toBe(0);
    // Returning creates a new owner that can still acquire the microphone.
    if (mode === "transcript") await page.getByRole("link", { name: "Transcript", exact: true }).click();
    else await page.getByRole("tab", { name: mode === "repetition" ? "Repetition" : "Roleplay", exact: true }).click();
    await page.getByRole("button", { name: buttonName, exact: true }).click();
    await expect.poll(() => page.evaluate(() => (window as unknown as { __micAudit: { requests: number } }).__micAudit.requests)).toBe(2);
    await page.evaluate(() => (window as unknown as { __micAudit: { grant: () => void } }).__micAudit.grant());
    await expect.poll(() => page.evaluate(() => (window as unknown as { __micAudit: { starts: number } }).__micAudit.starts)).toBe(1);
  });
}

test("hidden roleplay makes no request; activation opens exactly once", async ({ page }) => {
  let requests = 0;
  await page.route("https://api.groq.com/**", route => {
    requests++;
    return route.fulfill({ contentType: "application/json", body: JSON.stringify({ choices: [{ message: {
      content: "Response: Guten Tag!\nContext: A greeting.",
    } }] }) });
  });
  await configure(page);
  await page.goto("/practice");
  await page.waitForLoadState("networkidle");
  expect(requests).toBe(0);
  await page.getByRole("tab", { name: "Roleplay", exact: true }).click();
  await expect(page.getByText("Guten Tag!", { exact: true })).toBeVisible();
  expect(requests).toBe(1);
  await page.getByRole("tab", { name: "Repetition", exact: true }).click();
  await page.getByRole("tab", { name: "Roleplay", exact: true }).click();
  await expect(page.getByText("Guten Tag!", { exact: true })).toBeVisible();
  expect(requests).toBe(1);
});

test("a failed roleplay read preserves saved chat and Retry restores it", async ({ page }) => {
  let requests = 0;
  await page.route("https://api.groq.com/**", route => {
    requests++;
    return route.fulfill({ contentType: "application/json", body: JSON.stringify({ choices: [{ message: {
      content: "Response: SHOULD NOT REPLACE SAVED CHAT",
    } }] }) });
  });
  await configure(page);
  await page.goto("/transcript");
  await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open("deutschflow");
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction("roleplayMessages", "readwrite");
      tx.objectStore("roleplayMessages").put({ position: 0, scenario: "Ordering at a Berlin Bakery",
        role: "assistant", content: "Previously saved conversation", timestamp: 1000 });
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
    // A transient read fault, followed by working writes: the destructive fallback case.
    const original = IDBObjectStore.prototype.getAll;
    let fail = true;
    IDBObjectStore.prototype.getAll = function(...args: Parameters<IDBObjectStore["getAll"]>) {
      if (this.name === "roleplayMessages" && fail) {
        fail = false;
        throw new DOMException("Simulated transient read failure", "UnknownError");
      }
      return original.apply(this, args);
    };
  });
  await page.getByRole("link", { name: "Practice", exact: true }).click();
  await page.getByRole("tab", { name: "Roleplay", exact: true }).click();
  await expect(page.getByText("Your data could not be saved or read. Free up space or try again.", { exact: true })).toBeVisible();
  expect(requests).toBe(0);
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(page.getByText("Previously saved conversation", { exact: true })).toBeVisible();
  expect(requests).toBe(0);
});

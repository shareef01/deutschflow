import { test, expect, type Page } from "@playwright/test";

async function configure(page: Page) {
  await page.goto("/settings");
  await page.getByLabel("Groq API key", { exact: true }).fill("audit-test-key");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("A key is saved on this device.", { exact: true })).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "OK", exact: true }).click();
  await page.getByRole("radio", { name: "A1 (Beginner)", exact: true }).click();
  await expect(page.getByRole("radio", { name: "A1 (Beginner)", exact: true })).toBeChecked();
}

test("typed translation stays visible, saves its source, and passes CEFR to word details", async ({ page }) => {
  await page.addInitScript(() => {
    delete (window as unknown as Record<string, unknown>).SpeechRecognition;
    delete (window as unknown as Record<string, unknown>).webkitSpeechRecognition;
  });
  const prompts: string[] = [];
  await page.route("https://api.groq.com/**", async route => {
    const body = route.request().postDataJSON();
    prompts.push(body.messages[0].content);
    const details = body.messages.at(-1).content === "Morgen";
    const content = details
      ? { word: "Morgen", article: "der", plural: "Morgen", meaning: "morning", example_sentence: "Guten Morgen", synonyms: [], antonyms: [] }
      : { translation: "Good morning", keywords: ["Morgen"], example: "Guten Morgen", grammar: [] };
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ choices: [{ message: { content: JSON.stringify(content) } }] }) });
  });
  await configure(page);
  await page.goto("/transcript");
  await page.getByLabel("Type a German sentence", { exact: true }).fill("Guten Morgen");
  await page.getByRole("button", { name: "Translate", exact: true }).click();
  await expect(page.getByText("Good morning", { exact: true })).toBeVisible();
  await expect(page.getByRole("status").getByText("Guten Morgen", { exact: true })).toBeVisible();
  // The next typed input remains available after the result has appeared.
  await expect(page.getByLabel("Type a German sentence", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Save to library", exact: true }).click();
  await expect(page.getByText("Saved to your library.", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Morgen", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "Morgen", exact: true })).toBeVisible();
  expect(prompts).toHaveLength(2);
  for (const prompt of prompts) expect(prompt).toContain("Learner CEFR Level: A1");
  await page.goto("/vocabulary");
  await expect(page.getByText("Guten Morgen", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Good morning", { exact: true }).first()).toBeVisible();
});

test("roleplay respects autoplay for both opening and continuation", async ({ page }) => {
  await page.addInitScript(() => {
    const spoken: string[] = [];
    Object.defineProperty(window, "__spoken", { value: spoken });
    Object.defineProperty(window, "speechSynthesis", { value: {
      getVoices: () => [{ lang: "de-DE", localService: true }],
      speak: (utterance: { text: string }) => spoken.push(utterance.text),
      cancel: () => {}, onvoiceschanged: null,
    } });
    Object.defineProperty(window, "SpeechSynthesisUtterance", { value: class {
      text: string;
      constructor(text: string) { this.text = text; }
    } });
  });
  let turns = 0;
  await page.route("https://api.groq.com/**", route => {
    turns++;
    return route.fulfill({ contentType: "application/json", body: JSON.stringify({ choices: [{ message: {
      content: `Response: Antwort ${turns}\nContext: Reply ${turns}`,
    } }] }) });
  });
  await configure(page);
  const autoplay = page.getByRole("switch", { name: "Auto-play German audio", exact: true });
  await autoplay.click();
  await expect(autoplay).toHaveAttribute("aria-checked", "false");
  await page.goto("/practice");
  await page.getByRole("tab", { name: "Roleplay", exact: true }).click();
  await expect(page.getByText("Antwort 1", { exact: true })).toBeVisible();
  await page.getByPlaceholder("Type your message in German…").fill("Hallo");
  await page.getByRole("button", { name: "Send", exact: true }).click();
  await expect(page.getByText("Antwort 2", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Send", exact: true })).toBeDisabled();
  expect(await page.evaluate(() => (window as unknown as { __spoken: string[] }).__spoken)).toEqual([]);
  // Switching the preference back on applies to the next response in the restored conversation.
  await page.goto("/settings");
  await page.getByRole("switch", { name: "Auto-play German audio", exact: true }).click();
  await expect(page.getByRole("switch", { name: "Auto-play German audio", exact: true })).toHaveAttribute("aria-checked", "true");
  await page.goto("/practice");
  await page.getByRole("tab", { name: "Roleplay", exact: true }).click();
  await page.getByPlaceholder("Type your message in German…").fill("Danke");
  await page.getByRole("button", { name: "Send", exact: true }).click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { __spoken: string[] }).__spoken)).toEqual(["Antwort 3"]);
});

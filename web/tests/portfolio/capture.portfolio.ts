import { test, expect, type Page } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";

/**
 * Portfolio screenshots, captured from the real production build.
 *
 * Not part of the regression suite (it matches neither `*.spec.ts` nor a
 * baseline): run it on purpose with `npm run screenshots`. Every figure on
 * screen comes from the synthetic library below, restored through the app's own
 * backup import - so the pictures show what the import path really renders, and
 * no real learner data is ever involved. Groq is stubbed at the network layer
 * with fixed, clearly synthetic replies; nothing leaves the machine.
 */

const OUT = process.env.PORTFOLIO_OUT ?? path.resolve(__dirname, "../../../docs/screenshots/portfolio");

const DAY = 86_400_000;
const NOW = Date.now();

function dateKey(daysAgo: number) {
  const d = new Date(NOW - daysAgo * DAY);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

type Word = [german: string, english: string, article: string, plural: string, example: string,
  synonyms: string, interval: number, reviews: number, dueInDays: number];

const WORDS: Word[] = [
  ["Bahnhof", "train station", "der", "Bahnhöfe", "Wir treffen uns um acht am Bahnhof.", "Station", 0, 0, -1],
  ["Fahrkarte", "ticket", "die", "Fahrkarten", "Ich brauche eine Fahrkarte nach Hamburg.", "Ticket, Fahrschein", 1, 2, -1],
  ["Brötchen", "bread roll", "das", "Brötchen", "Zwei Brötchen, bitte.", "Semmel", 3, 3, 0],
  ["Termin", "appointment", "der", "Termine", "Ich habe morgen einen Termin beim Arzt.", "Verabredung", 6, 4, -1],
  ["Wohnung", "apartment", "die", "Wohnungen", "Die Wohnung hat drei Zimmer.", "Apartment", 24, 7, 12],
  ["Rechnung", "bill, invoice", "die", "Rechnungen", "Die Rechnung, bitte!", "Quittung", 30, 8, 20],
  ["sich erinnern", "to remember", "", "", "Ich erinnere mich an den Sommer in Wien.", "gedenken", 2, 2, 1],
  ["Wie spät ist es?", "What time is it?", "", "", "Entschuldigung, wie spät ist es?", "", 14, 5, 6],
  ["Gleis", "platform, track", "das", "Gleise", "Der Zug fährt von Gleis vier ab.", "Bahnsteig", 1, 1, -2],
  ["gemütlich", "cosy, comfortable", "", "", "Das Café ist sehr gemütlich.", "behaglich", 40, 9, 31],
  ["Kündigungsfrist", "notice period", "die", "Kündigungsfristen", "Die Kündigungsfrist beträgt drei Monate.", "", 0, 0, 0],
  ["Feierabend", "end of the working day", "der", "Feierabende", "Endlich Feierabend!", "Dienstschluss", 9, 4, 3],
];

function libraryBackup() {
  const vocabulary = WORDS.map(([germanText, englishTranslation, article, plural, exampleSentence, synonyms,
    interval, reviewCount, dueInDays], i) => ({
    germanText,
    englishTranslation,
    article,
    plural,
    exampleSentence,
    synonyms,
    antonyms: germanText === "gemütlich" ? "ungemütlich" : "",
    conjugation: germanText === "sich erinnern" ? "erinnert sich, erinnerte sich, hat sich erinnert" : "",
    timestamp: NOW - (WORDS.length - i) * DAY * 2,
    // The import rejects an incoherent schedule: a new word has no review date at all.
    nextReview: reviewCount === 0 ? 0 : NOW + dueInDays * DAY,
    interval,
    easeFactor: 2.5,
    reviewCount,
    remoteId: `portfolio-word-${i}`,
    lastModifiedAt: NOW - DAY,
  }));

  // A believable, irregular rhythm over the last twelve weeks - not a perfect run.
  const activityLog = [];
  for (let daysAgo = 83; daysAgo >= 1; daysAgo--) {
    const seed = (daysAgo * 37) % 11;
    if (seed < 4) continue;
    activityLog.push({ date: dateKey(daysAgo), xpGained: [10, 20, 35, 50, 60, 80, 110][seed % 7], timestamp: NOW - daysAgo * DAY });
  }
  activityLog.push({ date: dateKey(0), xpGained: 30, timestamp: NOW });

  return {
    format: "deutschflow-library",
    version: 2,
    exportedAt: new Date(NOW).toISOString(),
    vocabulary,
    transcripts: [
      { fullText: "Ich hätte gern zwei Brötchen und einen Kaffee.", timestamp: NOW - 2 * DAY, remoteId: "portfolio-t-1", lastModifiedAt: NOW - 2 * DAY },
      { fullText: "Wann fährt der nächste Zug nach München?", timestamp: NOW - DAY, remoteId: "portfolio-t-2", lastModifiedAt: NOW - DAY },
    ],
    userStats: [{ id: 1, xp: 2140, streak: 6, lastActivityTimestamp: NOW }],
    activityLog,
    reviewEvents: [],
    roleplayMessages: [],
  };
}

async function stubGroq(page: Page) {
  let roleplayTurn = 0;
  await page.route("https://api.groq.com/**", async (route) => {
    const body = route.request().postDataJSON();
    const system: string = body.messages[0].content;
    const last: string = body.messages.at(-1).content;
    let content: string;
    if (/roleplay|Response:/i.test(system)) {
      roleplayTurn++;
      content = roleplayTurn === 1
        ? "Response: Guten Tag! Was darf es heute sein?\nContext: Good afternoon! What would you like today?"
        : "Response: Gern! Möchten Sie die Brötchen in einer Tüte?\nContext: Of course! Would you like the rolls in a bag?";
    } else if (last === "Fahrkarte") {
      content = JSON.stringify({ word: "Fahrkarte", article: "die", plural: "Fahrkarten", meaning: "ticket",
        example_sentence: "Ich brauche eine Fahrkarte nach Hamburg.", synonyms: ["Fahrschein", "Ticket"], antonyms: [] });
    } else {
      content = JSON.stringify({
        translation: "When does the next train to Munich leave? I still need a ticket.",
        keywords: ["Zug", "Fahrkarte", "abfahren"],
        example: "Der Zug fährt um 9 Uhr ab.",
        grammar: [
          { phrase: "der nächste Zug", case: "Nominativ", why: "The train is the subject of the question." },
          { phrase: "eine Fahrkarte", case: "Akkusativ", why: "The ticket is the direct object of brauchen." },
        ],
      });
    }
    await route.fulfill({ contentType: "application/json",
      body: JSON.stringify({ choices: [{ message: { content }, finish_reason: "stop" }] }) });
  });
}

async function seed(page: Page) {
  await page.goto("/settings");
  await page.getByLabel("Groq API key", { exact: true }).fill("portfolio-synthetic-key");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "OK", exact: true }).click();
  await page.locator('input[type="file"]').setInputFiles({
    name: "portfolio-library.json",
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify(libraryBackup())),
  });
  await expect(page.getByRole("dialog")).toContainText(/restored|import completed/i);
  await page.getByRole("dialog").getByRole("button", { name: "OK", exact: true }).click();
}

const VIEWPORTS = [
  { name: "mobile", width: 390, height: 844 },
  { name: "desktop", width: 1440, height: 900 },
] as const;

for (const scheme of ["dark", "light"] as const) {
  for (const vp of VIEWPORTS) {
    test(`portfolio ${scheme} ${vp.name}`, async ({ page }) => {
      test.setTimeout(120_000);
      await mkdir(OUT, { recursive: true });
      await page.emulateMedia({ colorScheme: scheme, reducedMotion: "reduce" });
      await page.setViewportSize({ width: vp.width, height: vp.height });
      // Headless Chromium ships no German voice, so every card would carry the
      // "install a German voice" banner. A silent stand-in voice keeps the shots
      // about the screen rather than the CI machine; the banner itself is real
      // and covered by the TTS unit tests.
      await page.addInitScript(() => {
        Object.defineProperty(window, "speechSynthesis", { value: {
          getVoices: () => [{ lang: "de-DE", localService: true, name: "Deutsch" }],
          speak: () => {}, cancel: () => {}, onvoiceschanged: null,
          addEventListener: () => {}, removeEventListener: () => {},
        } });
        Object.defineProperty(window, "SpeechSynthesisUtterance", { value: class { constructor(public text: string) {} } });
      });
      await stubGroq(page);
      await seed(page);

      const shot = async (name: string) => {
        await page.waitForTimeout(250);
        await page.screenshot({ path: path.join(OUT, `${name}-${vp.name}-${scheme}.png`), animations: "disabled", caret: "hide" });
      };

      // Transcript - first use, then a typed sentence with its result.
      await page.goto("/transcript");
      await expect(page.locator("h1")).toBeVisible();
      await shot("transcript-empty");
      const typed = page.getByLabel("Type a German sentence", { exact: true });
      // Speak is the default input; Type is one press away, as for a learner.
      if (!(await typed.isVisible())) await page.getByRole("button", { name: "Type", exact: true }).click();
      await typed.fill("Wann fährt der nächste Zug nach München? Ich brauche noch eine Fahrkarte.");
      await page.getByRole("button", { name: "Translate", exact: true }).click();
      await expect(page.getByText(/When does the next train/).first()).toBeVisible();
      await shot("transcript-result");
      await page.getByRole("button", { name: "Fahrkarte", exact: true }).click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await shot("transcript-word-sheet");

      // Library, then one word's details.
      await page.goto("/vocabulary");
      await expect(page.getByText("Fahrkarte", { exact: true }).first()).toBeVisible();
      await shot("vocabulary");
      await page.getByText("Fahrkarte", { exact: true }).first().click();
      await shot("word-detail");

      // Study - dashboard, then a card before and after reveal.
      await page.goto("/study");
      await page.getByRole("tab", { name: "Dashboard" }).click();
      await shot("study-dashboard");
      await page.getByRole("tab", { name: "Flashcards" }).click();
      await shot("flashcard-front");
      await page.getByRole("button", { name: "Show the translation" }).first().click();
      await page.waitForTimeout(600);
      await shot("flashcard-back");

      // Practice - repetition, then a short roleplay.
      await page.goto("/practice");
      await shot("practice");
      await page.getByRole("tab", { name: "Roleplay", exact: true }).click();
      await expect(page.getByText("Guten Tag! Was darf es heute sein?")).toBeVisible();
      await page.getByPlaceholder("Type your message in German…").fill("Zwei Brötchen, bitte.");
      await page.getByRole("button", { name: "Send", exact: true }).click();
      await expect(page.getByText(/Möchten Sie die Brötchen/)).toBeVisible();
      await shot("roleplay");

      await page.goto("/settings");
      await shot("settings");
    });
  }
}

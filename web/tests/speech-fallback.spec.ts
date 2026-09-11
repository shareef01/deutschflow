import { test, expect } from '@playwright/test';

test.describe('Web Speech API Fallback', () => {
  // Overwrite the SpeechRecognition APIs to simulate an unsupported browser
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      const speechWindow = window as typeof window & {
        SpeechRecognition?: unknown;
        webkitSpeechRecognition?: unknown;
      };
      delete speechWindow.SpeechRecognition;
      delete speechWindow.webkitSpeechRecognition;
    });
  });

  test('Practice page displays fallback message and enables typed roleplay when Speech API is missing', async ({ page }) => {
    await page.goto('/practice');

    // Practice tabs still render (not all-or-nothing blocked)
    const tablist = page.getByRole('tablist');
    await expect(tablist).toBeVisible();

    // Repetition mode shows informative non-speech banner
    await expect(page.getByText(/Microphone practice is unavailable|Mikrofon-Übungen sind in diesem Browser nicht verfügbar/i)).toBeVisible();

    // Roleplay tab remains interactive with typed input
    const roleplayTab = tablist.getByRole('tab', { name: /roleplay|rollenspiel/i });
    await roleplayTab.click();
    await expect(page.getByText(/Voice input is unavailable|Spracheingabe ist in diesem Browser nicht verfügbar/i)).toBeVisible();
    await expect(page.getByRole('textbox')).toBeVisible();
    await expect(page.getByRole('button', { name: /send|senden/i })).toBeVisible();
  });
});

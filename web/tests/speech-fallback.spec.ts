import { test, expect } from '@playwright/test';

test.describe('Web Speech API Fallback', () => {
  // Overwrite the SpeechRecognition APIs to simulate an unsupported browser
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      delete (window as any).SpeechRecognition;
      delete (window as any).webkitSpeechRecognition;
    });
  });

  test('Practice page displays fallback message when Speech API is missing', async ({ page }) => {
    await page.goto('/practice');
    
    // The EmptyState component displays the fallback message
    await expect(page.getByText('This browser doesn\'t support the Web Speech API', { exact: false })).toBeVisible();
  });
});

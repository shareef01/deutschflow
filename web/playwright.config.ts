import { defineConfig } from "@playwright/test";
import { STORAGE_STATE, TEST_PASSWORD, TEST_SESSION_SECRET } from "./tests/global-setup";

/**
 * Playwright smoke — five tabs, the responsive breakpoint, and offline boot.
 * Serves the production build (the service worker only registers there).
 */
export default defineConfig({
  testDir: "./tests",
  testMatch: "**/*.spec.ts",
  snapshotPathTemplate: "{testDir}/../../docs/screenshots/web/{arg}{ext}",
  reporter: process.env.CI
    ? [["line"], ["html", { outputFolder: "playwright-report", open: "never" }]]
    : "line",
  timeout: 60_000,
  // Signs in once, so the suite exercises the app rather than the login screen.
  globalSetup: "./tests/global-setup.ts",
  use: {
    baseURL: "http://localhost:3200",
    storageState: STORAGE_STATE,
  },
  webServer: {
    command: process.env.CI
      ? "npx next start -p 3200"
      : "npm run build && npx next start -p 3200",
    url: "http://localhost:3200",
    // Local: reuse a server you already have running. CI: never - a leftover
    // server from a previous run would serve the old build to this one's tests.
    reuseExistingServer: false,
    timeout: 180_000,
    // The gate is real in the smoke run: the server checks this signature.
    env: { SITE_PASSWORD: TEST_PASSWORD, SESSION_SECRET: TEST_SESSION_SECRET },
  },
  projects: [
    { name: "chromium", use: { browserName: "chromium" } },
    {
      name: "firefox-smoke",
      testMatch: /(?:compatibility|speech-fallback)\.spec\.ts/,
      use: { browserName: "firefox" },
    },
    {
      name: "webkit-smoke",
      testMatch: /(?:compatibility|speech-fallback)\.spec\.ts/,
      use: { browserName: "webkit" },
    },
  ],
});

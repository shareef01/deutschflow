import { defineConfig } from "@playwright/test";
import { STORAGE_STATE, TEST_PASSWORD } from "./tests/global-setup";

/**
 * Playwright smoke — five tabs, the responsive breakpoint, and offline boot.
 * Serves the production build (the service worker only registers there).
 */
export default defineConfig({
  testDir: "./tests",
  testMatch: "**/*.spec.ts",
  timeout: 60_000,
  // Signs in once, so the suite exercises the app rather than the login screen.
  globalSetup: "./tests/global-setup.ts",
  use: {
    baseURL: "http://localhost:3200",
    storageState: STORAGE_STATE,
  },
  webServer: {
    command: "npm run build && npx next start -p 3200",
    url: "http://localhost:3200",
    reuseExistingServer: true,
    timeout: 180_000,
    // The gate is real in the smoke run: the server checks this signature.
    env: { SITE_PASSWORD: TEST_PASSWORD },
  },
  projects: [
    { name: "chromium", use: { browserName: "chromium" } },
  ],
});

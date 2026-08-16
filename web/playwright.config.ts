import { defineConfig } from "@playwright/test";

/**
 * Playwright smoke — five tabs, the responsive breakpoint, and offline boot.
 * Serves the production build (the service worker only registers there).
 */
export default defineConfig({
  testDir: "./tests",
  testMatch: "**/*.spec.ts",
  timeout: 60_000,
  use: {
    baseURL: "http://localhost:3200",
  },
  webServer: {
    command: "npm run build && npx next start -p 3200",
    url: "http://localhost:3200",
    reuseExistingServer: true,
    timeout: 180_000,
  },
  projects: [
    { name: "chromium", use: { browserName: "chromium" } },
  ],
});

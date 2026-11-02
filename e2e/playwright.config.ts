import { defineConfig } from "@playwright/test";

/**
 * Smoke test of the running app (T-074). It expects the app, built with its UI, at BASE_URL, on an empty database:
 * CI starts it in .github/workflows/ci.yml and `npm run test:local` does the same on this machine.
 * PW_CHANNEL=chrome uses an installed Google Chrome instead of the browser Playwright downloads.
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1, // the tests share one database and run in order
  // No retries: the tests share one database in order, so a retried test would find the data the first try left.
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.BASE_URL ?? "http://127.0.0.1:8080",
    channel: process.env.PW_CHANNEL || undefined,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});

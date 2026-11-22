import { defineConfig, devices } from '@playwright/test';

// The dashboard's own end-to-end tests (PRD-05 T7) drive the real stack: start it first with
// `make up` (or run `make dashboard-e2e`, which does both). Nothing here is mocked.
//
// One test at a time: the local stack has one worker with one slot, so runs from parallel tests
// would queue behind each other and make timings meaningless.
export default defineConfig({
  testDir: './e2e',
  timeout: 240_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});

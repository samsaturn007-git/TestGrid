import { defineConfig, devices } from '@playwright/test';
import { fixtureEnv } from './suites/support/shop-test.ts';

// Timeouts are chosen against the demo-shop fixture timings:
//   AUTOMATION_FLAKE late confirmation (8 s) > expect timeout (5 s)   → assertion fails
//   SELECTOR_DRIFT missing test id: actionTimeout (5 s)               → locator error
//   TIMEOUT order hang (120 s) > test timeout (20 s)                  → test timeout
export default defineConfig({
  timeout: 20_000,
  expect: { timeout: 5_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // Matches the manifest's playwrightRetries for checkout-smoke (contracts §10).
  retries: 1,
  workers: 3,
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/results.json' }],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
  ],
  use: {
    baseURL: fixtureEnv.baseUrl,
    actionTimeout: 5_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', testDir: './suites', use: { ...devices['Desktop Chrome'] } },
    {
      name: 'fixture-checks',
      testDir: './fixture-checks',
      retries: 0,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});

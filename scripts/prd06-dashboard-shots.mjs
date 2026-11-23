// PRD-06 evidence: screenshots of a run's timeline section and of a case drawer, taken with the
// pinned Playwright Chromium (headless) against a running dashboard.
// Usage: node scripts/prd06-dashboard-shots.mjs <baseUrl> <runId> <caseKey> <engine> <outDir>
import { createRequire } from 'node:module';
import path from 'node:path';
const require = createRequire(new URL('../worker/package.json', import.meta.url));
const { chromium } = require('@playwright/test');

const [base, runId, caseKey, engine, outDir] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({
  viewport: { width: 1440, height: 1000 },
  deviceScaleFactor: 1,
});
await page.goto(`${base}/runs/${runId}`);
const timeline = page.getByRole('region', { name: 'Timeline' });
await timeline
  .getByText(/heartbeats?/)
  .first()
  .waitFor();
await timeline.screenshot({ path: path.join(outDir, 'prd-06-ac5-timeline.png') });
await page
  .getByRole('button', { name: new RegExp(`^${caseKey} on ${engine}:.*Open details$`) })
  .click();
const drawer = page.getByRole('dialog');
const shot = drawer.getByRole('img', { name: /^Screenshot of/ });
await shot.waitFor();
await page.waitForFunction(
  (img) => img.complete && img.naturalWidth > 0,
  await shot.elementHandle(),
);
await drawer.getByLabel('Log tail, redacted').waitFor();
await drawer.screenshot({ path: path.join(outDir, 'prd-06-case-drawer.png') });
console.log(
  'saved',
  path.join(outDir, 'prd-06-ac5-timeline.png'),
  path.join(outDir, 'prd-06-case-drawer.png'),
);
await browser.close();

// PRD-06 AC-1 evidence: open a trace served by 'playwright show-trace --host 127.0.0.1 --port 9323 <trace.zip>'
// in the pinned Playwright Chromium (headless), select the failing cart-total assertion and its
// Errors tab, and save a screenshot. Usage: node scripts/trace-viewer-shot.mjs <out.png>
import { createRequire } from 'node:module';
const require = createRequire(new URL('../worker/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const out = process.argv[2];
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await page.goto('http://127.0.0.1:9323');
// The action list appears once the trace has loaded.
const actions = page.locator('.action-title');
await actions.first().waitFor({ timeout: 30000 });
const titles = await actions.allInnerTexts();
console.log('actions:', titles.length);
console.log(titles.join(' | '));
await page.getByText("getByTestId('cart-total')", { exact: true }).first().click();
await page.getByText('Errors', { exact: true }).first().click();
await page.waitForTimeout(1500);
await page.screenshot({ path: out });
const errors = await page
  .locator('.tab-errors, .error-message')
  .allInnerTexts()
  .catch(() => []);
console.log('error panel:', errors.join(' ').slice(0, 300));
await browser.close();

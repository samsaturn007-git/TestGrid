// Test double for the Playwright CLI used by test/attempt.test.ts. It reads only what the real
// CLI would get (argv and the allowlisted env) and writes a recorded report to
// PLAYWRIGHT_JSON_OUTPUT_FILE. Seed selects odd behaviours: 1 = crash without a report,
// 2 = truncated report, 3 = hang until killed.
import { copyFileSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const dir = path.join(import.meta.dirname, '..', 'playwright-1.65.1');
const out = process.env.PLAYWRIGHT_JSON_OUTPUT_FILE;
const seed = process.env.TG_FIXTURE_SEED;
writeFileSync(out + '.argv.json', JSON.stringify(process.argv.slice(2)));
if (seed === '1') process.exit(139);
// TG_FAULT=browser_launch: what Playwright reports when the browser executable is missing.
if (process.env.PLAYWRIGHT_BROWSERS_PATH?.startsWith('/nonexistent/')) {
  const report = JSON.parse(
    readFileSync(path.join(dir, 'checkout-smoke-chromium-product-defect.json'), 'utf8'),
  );
  const message = `Error: browserType.launch: Executable doesn't exist at ${process.env.PLAYWRIGHT_BROWSERS_PATH}/chromium_headless_shell-1200/chrome-linux/headless_shell`;
  const fail = (node) => {
    for (const spec of node.specs ?? []) {
      for (const test of spec.tests) {
        test.status = 'unexpected';
        test.results = [{ status: 'failed', duration: 12, retry: 0, error: { message }, errors: [{ message }], attachments: [] }];
      }
    }
    for (const child of node.suites ?? []) fail(child);
  };
  report.suites.forEach(fail);
  writeFileSync(out, JSON.stringify(report));
  process.exit(1);
}
if (seed === '3') setInterval(() => {}, 1000);
else {
  const file =
    seed === '2'
      ? 'truncated.json'
      : `checkout-smoke-chromium-${process.env.TG_FIXTURE_MODE.toLowerCase().replace('_', '-')}.json`;
  copyFileSync(path.join(dir, file), out);
  process.exit(1);
}

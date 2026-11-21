// Test double for the Playwright CLI used by test/attempt.test.ts. It reads only what the real
// CLI would get (argv and the allowlisted env) and writes a recorded report to
// PLAYWRIGHT_JSON_OUTPUT_FILE. Seed selects odd behaviours: 1 = crash without a report,
// 2 = truncated report, 3 = hang until killed.
import { copyFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const dir = path.join(import.meta.dirname, '..', 'playwright-1.65.1');
const out = process.env.PLAYWRIGHT_JSON_OUTPUT_FILE;
const seed = process.env.TG_FIXTURE_SEED;
writeFileSync(out + '.argv.json', JSON.stringify(process.argv.slice(2)));
if (seed === '1') process.exit(139);
if (seed === '3') setInterval(() => {}, 1000);
else {
  const file =
    seed === '2'
      ? 'truncated.json'
      : `checkout-smoke-chromium-${process.env.TG_FIXTURE_MODE.toLowerCase().replace('_', '-')}.json`;
  copyFileSync(path.join(dir, file), out);
  process.exit(1);
}

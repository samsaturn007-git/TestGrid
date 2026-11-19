#!/usr/bin/env node
// Prints one line per case from a Playwright JSON report:
//   <tag> <outcome> <per-try statuses> <first line of the last error>
// Usage: node scripts/summarize-playwright.mjs worker/test-results/results.json
import { readFileSync } from 'node:fs';

const file = process.argv[2];
if (!file) {
  console.error('usage: summarize-playwright.mjs <results.json>');
  process.exit(3);
}
const report = JSON.parse(readFileSync(file, 'utf8'));

// Playwright error messages carry ANSI colour codes; strip them for stable text.
// eslint-disable-next-line no-control-regex
const plain = (s) => s.replace(/\u001b\[[0-9;]*m/g, '');

const rows = [];
function walk(suite) {
  for (const spec of suite.specs ?? []) {
    for (const t of spec.tests) {
      const tries = t.results.map((r) => r.status);
      const lastError = [...t.results].reverse().find((r) => r.error)?.error?.message ?? '';
      const lines = plain(lastError)
        .split('\n')
        .map((l) => l.trim())
        .filter(Boolean);
      // First line plus any Expected/Received lines: enough to compare messages across runs.
      const detail = [lines[0] ?? '', ...lines.filter((l) => /^(Expected|Received)/.test(l))];
      rows.push({
        tag: spec.tags.map((x) => (x.startsWith('@') ? x : `@${x}`)).join(',') || spec.title,
        outcome: t.status, // expected | unexpected | flaky | skipped
        tries: tries.join('>'),
        error: detail.join(' | ').slice(0, 200),
      });
    }
  }
  for (const child of suite.suites ?? []) walk(child);
}
for (const s of report.suites) walk(s);

rows.sort((a, b) => a.tag.localeCompare(b.tag));
for (const r of rows) {
  console.log(`${r.tag.padEnd(22)} ${r.outcome.padEnd(10)} ${r.tries.padEnd(18)} ${r.error}`);
}
const s = report.stats;
console.log(
  `stats: expected=${s.expected} unexpected=${s.unexpected} flaky=${s.flaky} skipped=${s.skipped}`,
);

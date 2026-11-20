// PRD-03 live end-to-end check (E2E-001, AC-1, AC-2): submit runs through the public API,
// let the Compose worker execute them in a real browser, and check what the database says.
// Usage: node scripts/e2e.mjs [engine ...]   (default: chromium). Exit 0 only if every
// scenario matches its expected outcome.
import { randomUUID } from 'node:crypto';

const API = process.env.API_URL ?? 'http://127.0.0.1:8080';
const PROJECT = '00000000-0000-4000-8000-000000000001';
const engines = process.argv.slice(2).length ? process.argv.slice(2) : ['chromium'];
const TERMINAL = new Set(['PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED']);

const SCENARIOS = [
  { suite: 'checkout-smoke', mode: 'PASS', run: 'PASSED', cases: 6, expect: {} },
  {
    suite: 'checkout-smoke',
    mode: 'PRODUCT_DEFECT',
    run: 'FAILED',
    cases: 6,
    expect: { 'cart-total': ['FAILED', 'ASSERTION'] },
  },
  {
    suite: 'checkout-smoke',
    mode: 'SELECTOR_DRIFT',
    run: 'FAILED',
    cases: 6,
    expect: { 'checkout-happy-path': ['FAILED', 'SELECTOR_NOT_FOUND'] },
  },
  {
    suite: 'checkout-smoke',
    mode: 'TIMEOUT',
    run: 'TIMED_OUT',
    cases: 6,
    expect: { 'checkout-happy-path': ['TIMED_OUT', 'TEST_TIMEOUT'] },
  },
  { suite: 'catalog-regression', mode: 'PASS', run: 'PASSED', cases: 12, expect: {} },
];

async function json(path, init) {
  const res = await fetch(API + path, {
    ...init,
    headers: { 'content-type': 'application/json' },
  });
  const body = await res.json();
  if (res.status >= 400) throw new Error(`${path} → ${res.status} ${JSON.stringify(body)}`);
  return body;
}

async function runScenario(s) {
  const started = Date.now();
  const run = await json(`/api/v1/projects/${PROJECT}/runs`, {
    method: 'POST',
    body: JSON.stringify({
      suiteKey: s.suite,
      targets: engines.map((engine) => ({ executor: 'LOCAL', engine })),
      trigger: 'MANUAL',
      idempotencyKey: `e2e-${randomUUID()}`,
      fixture: { mode: s.mode, seed: 42 },
    }),
  });
  let detail;
  for (;;) {
    detail = await json(`/api/v1/runs/${run.id}`);
    if (TERMINAL.has(detail.status)) break;
    if (Date.now() - started > 15 * 60_000) throw new Error(`run ${run.id} not terminal in 15 min`);
    await new Promise((r) => setTimeout(r, 1000));
  }
  const results = await json(`/api/v1/runs/${run.id}/results`);
  const problems = [];
  if (detail.status !== s.run) problems.push(`run ${detail.status}, expected ${s.run}`);
  for (const target of results.targets) {
    const latest = target.attempts[0];
    const cases = latest?.caseResults ?? [];
    if (cases.length !== s.cases) problems.push(`${target.targetKey}: ${cases.length} cases`);
    for (const [key, [status, errorClass]] of Object.entries(s.expect)) {
      const c = cases.find((x) => x.caseKey === key);
      if (c?.status !== status || c?.errorClass !== errorClass) {
        problems.push(`${target.targetKey} ${key}: ${c?.status}/${c?.errorClass}`);
      }
    }
    const summary = cases
      .filter((c) => c.status !== 'PASSED')
      .map((c) => `${c.caseKey}=${c.status}${c.errorClass ? `/${c.errorClass}` : ''}`)
      .join(' ');
    console.log(
      `  ${target.targetKey} attempt ${latest?.attempt.attemptNo} ${target.status}` +
        ` cases=${cases.length} ${summary || '(all PASSED)'}`,
    );
  }
  const secs = ((Date.now() - started) / 1000).toFixed(1);
  console.log(
    `${problems.length ? 'FAIL' : 'ok  '} ${s.suite} ${s.mode} → ${detail.status} in ${secs}s run=${run.id}`,
  );
  for (const p of problems) console.log(`     ${p}`);
  return problems.length === 0;
}

let ok = true;
console.log(`e2e: engines ${engines.join(', ')} against ${API}`);
for (const s of SCENARIOS) ok = (await runScenario(s)) && ok;
console.log(ok ? 'e2e OK' : 'e2e FAILED');
process.exit(ok ? 0 : 1);

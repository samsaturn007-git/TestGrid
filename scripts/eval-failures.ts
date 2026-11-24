// PRD-07 FR-8 (T6): the failure classifier against labelled ground truth.
//
// Runs the 6 labelled scenarios (SECURITY_AND_TEST_STRATEGY "Fixture and fault scenarios"), each
// at least 5 times, against the live Compose stack through the public API, and compares the
// category each failure got with its label:
//   PASS → no failure, PRODUCT_DEFECT → PRODUCT_SUSPECTED, AUTOMATION_FLAKE → AUTOMATION_FLAKE,
//   SELECTOR_DRIFT → TEST_BROKEN, TIMEOUT → TIMEOUT, BROWSER_LAUNCH (worker fault) → ENVIRONMENT.
// Writes docs/evidence/data/failure-eval.csv (one row per failure occurrence, plus one per run that
// produced none) and failure-eval.md (confusion matrices, precision and recall).
//
// Usage (needs `make up` with TESTGRID_PROFILE=local; CONFLICT_REGISTER N29):
//   node scripts/eval-failures.ts [--runs 5] [--engines chromium] [--allow-existing]
// API_URL defaults to http://127.0.0.1:8080. The BROWSER_LAUNCH scenario recreates the Compose
// worker with TG_FAULT_WORKER=browser_launch (N27) and puts it back afterwards.
//
// Scenarios run in blocks, in a fixed order, one run at a time: case health is a rolling window
// per case, so interleaving PASS and PRODUCT_DEFECT runs would itself make cases flip (contracts
// §8.3). A project that already has runs is refused, because earlier history changes the labels.

import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';
import type { components } from '../packages/contracts/src/api.ts';

type Schemas = components['schemas'];
type Category = Schemas['FailureGroupView']['category'];
type Engine = Schemas['RunTarget']['engine'];
type FixtureMode = NonNullable<Schemas['CreateRunRequest']['fixture']>['mode'];

const ROOT = path.join(import.meta.dirname, '..');
const API = process.env.API_URL ?? 'http://127.0.0.1:8080';
const PROJECT = '00000000-0000-4000-8000-000000000001';
const SUITE = 'checkout-smoke';
const BRANCH = 'eval-failures';
const RUN_DEADLINE_MS = 10 * 60_000;
const NONE = 'NONE';

interface Scenario {
  name: string;
  fixture: FixtureMode;
  fault: boolean;
  expected: Category | typeof NONE;
}

const SCENARIOS: Scenario[] = [
  { name: 'PASS', fixture: 'PASS', fault: false, expected: NONE },
  {
    name: 'PRODUCT_DEFECT',
    fixture: 'PRODUCT_DEFECT',
    fault: false,
    expected: 'PRODUCT_SUSPECTED',
  },
  {
    name: 'AUTOMATION_FLAKE',
    fixture: 'AUTOMATION_FLAKE',
    fault: false,
    expected: 'AUTOMATION_FLAKE',
  },
  { name: 'SELECTOR_DRIFT', fixture: 'SELECTOR_DRIFT', fault: false, expected: 'TEST_BROKEN' },
  { name: 'TIMEOUT', fixture: 'TIMEOUT', fault: false, expected: 'TIMEOUT' },
  { name: 'BROWSER_LAUNCH', fixture: 'PASS', fault: true, expected: 'ENVIRONMENT' },
];

const LABELS: (Category | typeof NONE)[] = [
  'PRODUCT_SUSPECTED',
  'TEST_BROKEN',
  'AUTOMATION_FLAKE',
  'TIMEOUT',
  'ENVIRONMENT',
  'UNKNOWN',
  NONE,
];

interface Row {
  scenario: string;
  runNo: number;
  seed: number;
  runId: string;
  runStatus: string;
  engine: Engine | '';
  caseKey: string;
  status: string;
  errorClass: string;
  groupId: string;
  fingerprint: string;
  occurrencesSoFar: number;
  predicted: Category | typeof NONE;
  ruleId: string;
  confidence: string;
  /** The matched rule's reason when the run finished: why it got that label then. */
  ruleReason: string;
  final: Category | typeof NONE;
  expected: Category | typeof NONE;
}

const { values: args } = parseArgs({
  options: {
    runs: { type: 'string', default: '5' },
    engines: { type: 'string', default: 'chromium' },
    'allow-existing': { type: 'boolean', default: false },
    out: { type: 'string', default: path.join(ROOT, 'docs', 'evidence', 'data') },
  },
});
const RUNS = Number(args.runs);
if (!Number.isInteger(RUNS) || RUNS < 5 || RUNS > 50)
  throw new Error('--runs must be 5..50 (FR-8)');
const ENGINES = args.engines.split(',').map((e) => e.trim()) as Engine[];
for (const e of ENGINES) {
  if (!['chromium', 'firefox', 'webkit'].includes(e)) throw new Error(`unknown engine ${e}`);
}

async function api<T>(method: string, route: string, body?: unknown): Promise<T> {
  const res = await fetch(API + '/api/v1' + route, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${route} → ${res.status} ${text.slice(0, 300)}`);
  return JSON.parse(text) as T;
}

/**
 * Run n's fixture seed: Knuth's multiplicative hash, n × 2654435761 mod (2³¹ − 1). A fixed
 * formula rather than chosen seeds. The demo shop feeds the seed straight into mulberry32, whose
 * first draw is "on time" for every seed from 1 to 6, so seeds 1..5 would never show the
 * AUTOMATION_FLAKE fixture's late confirmation at all.
 */
export function seedOf(runNo: number): number {
  return Number((BigInt(runNo) * 2654435761n) % 2147483647n);
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Recreates the Compose worker with or without the launch fault, as scripts/crash-check.sh does. */
function worker(fault: string) {
  const res = spawnSync(
    'docker',
    [
      'compose',
      '-f',
      'infra/compose/compose.yaml',
      '--env-file',
      '.env',
      'up',
      '-d',
      '--force-recreate',
      '--no-deps',
      '--wait',
      'worker',
    ],
    { cwd: ROOT, env: { ...process.env, TG_FAULT_WORKER: fault }, encoding: 'utf8' },
  );
  if (res.status !== 0) throw new Error(`docker compose up worker failed: ${res.stderr}`);
}

async function waitForWorkers(minimum: number) {
  for (let i = 0; i < 60; i++) {
    const w = await api<Schemas['WorkersResponse']>('GET', '/workers');
    const active = w.items.filter((x) => x.state === 'ACTIVE');
    if (active.length >= minimum) return;
    await sleep(1000);
  }
  throw new Error('no ACTIVE worker registered within 60 s');
}

async function runOnce(s: Scenario, runNo: number): Promise<Schemas['RunResults']> {
  const seed = seedOf(runNo);
  const created = await api<Schemas['RunResponse']>('POST', `/projects/${PROJECT}/runs`, {
    suiteKey: SUITE,
    targets: ENGINES.map((engine) => ({ executor: 'LOCAL', engine })),
    trigger: 'MANUAL',
    idempotencyKey: `eval-${s.name}-${runNo}-${Date.now()}`,
    fixture: { mode: s.fixture, seed },
    source: { branch: BRANCH },
  });
  const started = Date.now();
  for (;;) {
    const run = await api<Schemas['RunResponse']>('GET', `/runs/${created.id}`);
    if (['PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED'].includes(run.status)) break;
    if (Date.now() - started > RUN_DEADLINE_MS) throw new Error(`run ${created.id} did not finish`);
    await sleep(1000);
  }
  return api<Schemas['RunResults']>('GET', `/runs/${created.id}/results`);
}

/** One row per failure occurrence of the run, with its group's label right after the run. */
async function rowsOf(s: Scenario, runNo: number, results: Schemas['RunResults']): Promise<Row[]> {
  const rows: Row[] = [];
  const base = {
    scenario: s.name,
    runNo,
    seed: seedOf(runNo),
    runId: results.runId,
    runStatus: results.status,
    expected: s.expected,
    final: NONE as Category | typeof NONE,
  };
  for (const target of results.targets) {
    const latest = target.attempts[0];
    if (!latest) continue;
    const occurrences: { caseKey: string; status: string; errorClass: string; groupId: string }[] =
      latest.caseResults
        .filter((c) => c.fingerprintId)
        .map((c) => ({
          caseKey: c.caseKey,
          status: c.status,
          errorClass: c.errorClass ?? '',
          groupId: c.fingerprintId ?? '',
        }));
    if (latest.fingerprintId && latest.caseResults.length === 0) {
      occurrences.push({
        caseKey: '(whole target)',
        status: latest.attempt.status,
        errorClass: latest.attempt.errorClass ?? '',
        groupId: latest.fingerprintId,
      });
    }
    for (const o of occurrences) {
      const g = await api<Schemas['FailureGroupView']>('GET', `/failure-groups/${o.groupId}`);
      rows.push({
        ...base,
        engine: target.engine,
        ...o,
        fingerprint: g.fingerprint,
        occurrencesSoFar: g.occurrenceCount,
        // The classifier's own label: an override is a human's answer, not a prediction.
        predicted: g.ruleCategory,
        ruleId: g.ruleId,
        confidence: g.confidence.toFixed(2),
        ruleReason: g.evidence.rulesTried.find((r) => r.matched)?.reason ?? '',
      });
    }
  }
  if (rows.length === 0) {
    rows.push({
      ...base,
      engine: '',
      caseKey: '',
      status: results.status,
      errorClass: '',
      groupId: '',
      fingerprint: '',
      occurrencesSoFar: 0,
      predicted: NONE,
      ruleId: '',
      confidence: '',
      ruleReason: '',
    });
  }
  return rows;
}

function csv(rows: Row[]): string {
  const header = [
    'scenario',
    'run_no',
    'seed',
    'run_id',
    'run_status',
    'engine',
    'case_key',
    'status',
    'error_class',
    'group_id',
    'fingerprint',
    'occurrences_so_far',
    'predicted_after_run',
    'rule_id',
    'confidence',
    'rule_reason',
    'final_category',
    'expected',
    'correct_after_run',
    'correct_final',
  ];
  const esc = (v: string | number | boolean) =>
    /[",\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : String(v);
  const lines = rows.map((r) =>
    [
      r.scenario,
      r.runNo,
      r.seed,
      r.runId,
      r.runStatus,
      r.engine,
      r.caseKey,
      r.status,
      r.errorClass,
      r.groupId,
      r.fingerprint,
      r.occurrencesSoFar,
      r.predicted,
      r.ruleId,
      r.confidence,
      r.ruleReason,
      r.final,
      r.expected,
      r.predicted === r.expected,
      r.final === r.expected,
    ]
      .map(esc)
      .join(','),
  );
  return [header.join(','), ...lines].join('\n') + '\n';
}

function matrix(rows: Row[], pick: (r: Row) => Category | typeof NONE): string {
  const counts = new Map<string, number>();
  for (const r of rows) {
    const key = `${r.expected}|${pick(r)}`;
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const head = `| expected ↓ / predicted → | ${LABELS.join(' | ')} | total |`;
  const sep = `|---|${LABELS.map(() => '---:').join('|')}|---:|`;
  const body = LABELS.filter((e) => rows.some((r) => r.expected === e)).map((e) => {
    const cells = LABELS.map((p) => counts.get(`${e}|${p}`) ?? 0);
    const total = cells.reduce((a, b) => a + b, 0);
    return `| **${e}** | ${cells.map((c, i) => (LABELS[i] === e ? `**${c}**` : String(c))).join(' | ')} | ${total} |`;
  });
  return [head, sep, ...body].join('\n');
}

/** Per label: precision = TP / predicted as it, recall = TP / labelled as it. */
function precisionRecall(rows: Row[], pick: (r: Row) => Category | typeof NONE): string {
  const lines = [
    '| label | TP | predicted | labelled | precision | recall |',
    '|---|---:|---:|---:|---:|---:|',
  ];
  for (const label of LABELS) {
    const tp = rows.filter((r) => r.expected === label && pick(r) === label).length;
    const predicted = rows.filter((r) => pick(r) === label).length;
    const labelled = rows.filter((r) => r.expected === label).length;
    if (predicted === 0 && labelled === 0) continue;
    const ratio = (n: number, d: number) => (d === 0 ? 'n/a' : (n / d).toFixed(2));
    lines.push(
      `| ${label} | ${tp} | ${predicted} | ${labelled} | ${ratio(tp, predicted)} | ${ratio(tp, labelled)} |`,
    );
  }
  return lines.join('\n');
}

/** Every row whose label after the run or final label is not its ground truth (T7). */
function misclassified(rows: Row[]): string[] {
  const wrong = rows.filter((r) => r.predicted !== r.expected || r.final !== r.expected);
  if (wrong.length === 0) return ['None.'];
  const cell = (v: string) => v.replace(/\|/g, '\\|');
  return [
    '| scenario | run | engine | case | expected | after run | rule, confidence and reason then | final |',
    '|---|---:|---|---|---|---|---|---|',
    ...wrong.map(
      (r) =>
        `| ${r.scenario} | ${r.runNo} | ${r.engine || '-'} | ${r.caseKey || '-'} | ${r.expected} | ${r.predicted} | ${r.ruleId ? `${r.ruleId} ${r.confidence}: ${cell(r.ruleReason)}` : '-'} | ${r.final} |`,
    ),
  ];
}

function git(...a: string[]): string {
  return spawnSync('git', a, { cwd: ROOT, encoding: 'utf8' }).stdout.trim();
}

async function main() {
  const caps = await api<Schemas['Capabilities']>('GET', '/capabilities');
  if (!caps.fixturesAllowed)
    throw new Error('fixtures are not allowed: set TESTGRID_PROFILE=local');
  const existing = await api<Schemas['PageRunSummary']>('GET', '/runs?limit=1');
  if (existing.items.length > 0 && !args['allow-existing']) {
    throw new Error(
      'the project already has runs, and their history changes the labels. Run `make reset` first, or pass --allow-existing (the results are then not from a clean start).',
    );
  }
  await waitForWorkers(1);
  const startedAt = new Date().toISOString();
  // The code that ran is the tree at the start: docs edited during a 25-minute run don't count.
  const commit = git('rev-parse', '--short', 'HEAD');
  const dirty = git('status', '--porcelain') !== '';
  const rows: Row[] = [];
  const runLog: string[] = [];
  for (const s of SCENARIOS) {
    if (s.fault) {
      console.log(`${s.name}: recreating the worker with TG_FAULT_WORKER=browser_launch`);
      worker('browser_launch');
      await waitForWorkers(1);
    }
    try {
      for (let runNo = 1; runNo <= RUNS; runNo++) {
        const t0 = Date.now();
        const results = await runOnce(s, runNo);
        const got = await rowsOf(s, runNo, results);
        rows.push(...got);
        const summary = got.map((r) => `${r.caseKey || '-'}:${r.predicted}`).join(' ');
        const line = `${s.name} #${runNo} (seed ${seedOf(runNo)}) ${results.status} in ${((Date.now() - t0) / 1000).toFixed(0)} s: ${summary}`;
        runLog.push(line);
        console.log(line);
      }
    } finally {
      if (s.fault) {
        console.log(`${s.name}: putting the worker back without a fault`);
        worker('');
        await waitForWorkers(1);
      }
    }
  }

  // The label each group ended with, after every scenario ran.
  const finals = new Map<string, Category>();
  for (const id of new Set(rows.map((r) => r.groupId).filter(Boolean))) {
    finals.set(
      id,
      (await api<Schemas['FailureGroupView']>('GET', `/failure-groups/${id}`)).ruleCategory,
    );
  }
  for (const r of rows) r.final = r.groupId ? (finals.get(r.groupId) ?? NONE) : NONE;

  // AC-3: the flake fixture's cases and their health labels.
  const flakeCases = [
    ...new Set(
      rows
        .filter(
          (r) => r.scenario === 'AUTOMATION_FLAKE' && r.groupId && r.caseKey !== '(whole target)',
        )
        .map((r) => r.caseKey),
    ),
  ];
  const health: string[] = [];
  for (const caseKey of flakeCases) {
    const h = await api<Schemas['CaseHistoryView']>(
      'GET',
      `/cases/${caseKey}/history?suiteKey=${SUITE}`,
    );
    for (const e of h.engines.filter((x) => x.health)) {
      health.push(
        `| ${caseKey} | ${e.engine} | ${e.health?.label} | ${e.health?.recentOutcomes} | ${e.health?.reason} |`,
      );
    }
  }

  mkdirSync(args.out, { recursive: true });
  writeFileSync(path.join(args.out, 'failure-eval.csv'), csv(rows));
  const online = (r: Row) => r.predicted;
  const final = (r: Row) => r.final;
  const correct = (pick: (r: Row) => string) => rows.filter((r) => pick(r) === r.expected).length;
  const md = [
    '# Failure classifier evaluation (PRD-07 FR-8)',
    '',
    `Generated by \`node scripts/eval-failures.ts --runs ${RUNS} --engines ${ENGINES.join(',')}\` against ${API}, from ${startedAt} to ${new Date().toISOString()}, at commit \`${commit}\`${dirty ? ' (with uncommitted changes when it started)' : ' (clean tree when it started)'}. Live: the Compose stack with the real worker, demo shop and Playwright. Raw rows: \`failure-eval.csv\`.`,
    '',
    'Each row is one failure occurrence (a case result, or a whole attempt that failed to run), or one run that produced no failure (`NONE`). "After run" is the rule\'s label for its group right after that run finished, the way a user saw it then. "Final" is the rule\'s label after every scenario had run. Overrides are never counted: they are a human\'s answer, not a prediction.',
    '',
    `Rows: ${rows.length}. Correct after run: ${correct(online)} of ${rows.length}. Correct final: ${correct(final)} of ${rows.length}.`,
    '',
    '## Confusion matrix, label after each run',
    '',
    matrix(rows, online),
    '',
    precisionRecall(rows, online),
    '',
    '## Confusion matrix, final label',
    '',
    matrix(rows, final),
    '',
    precisionRecall(rows, final),
    '',
    '## Misclassified rows',
    '',
    ...misclassified(rows),
    '',
    '## AC-3: health of the cases the AUTOMATION_FLAKE fixture failed',
    '',
    '| case | engine | label | window | reason |',
    '|---|---|---|---|---|',
    ...health,
    '',
    '## Runs',
    '',
    ...runLog.map((l) => `- ${l}`),
    '',
  ].join('\n');
  writeFileSync(path.join(args.out, 'failure-eval.md'), md);
  console.log(`\nwrote ${path.join(args.out, 'failure-eval.csv')} and failure-eval.md`);
  console.log(
    `correct after run ${correct(online)}/${rows.length}, final ${correct(final)}/${rows.length}`,
  );
}

await main();

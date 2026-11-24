import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { executeAttempt } from '../src/attempt.ts';
import type { AttemptDeps } from '../src/attempt.ts';
import type { Claim } from '../src/claim.ts';
import { DEFAULT_WORKER_ROOT, loadManifest, suiteByKey } from '../src/manifest.ts';

const suites = loadManifest();
const revision = suiteByKey(suites, 'checkout-smoke').revision;
const SECRET = 'service-token-must-never-appear-0123456789';

function claim(mode: NonNullable<Claim['fixture']>['mode'], seed = 42): Claim {
  return {
    attemptId: '00000000-0000-4000-8000-0000000000a1',
    attemptNo: 1,
    runId: '00000000-0000-4000-8000-0000000000b1',
    targetId: '00000000-0000-4000-8000-0000000000c1',
    leaseToken: 'A'.repeat(43),
    leaseTtlSeconds: 30,
    heartbeatIntervalSeconds: 10,
    suite: { suiteKey: 'checkout-smoke', revision },
    engine: 'chromium',
    caseKeys: [
      'add-to-cart',
      'apply-coupon',
      'browse-catalog',
      'card-declined',
      'cart-total',
      'checkout-happy-path',
    ],
    fixture: { mode, seed },
    timeoutMs: 120_000,
  };
}

function deps(spawned: string[][] = []): AttemptDeps {
  return {
    suites,
    workerRoot: DEFAULT_WORKER_ROOT,
    configPath: path.join(DEFAULT_WORKER_ROOT, 'playwright.config.ts'),
    baseUrl: 'http://127.0.0.1:4173',
    secrets: [SECRET],
    parentEnv: { ...process.env, TESTGRID_WORKER_TOKEN: SECRET },
    killGraceMs: 200,
    cliPath: path.join(import.meta.dirname, 'fixtures', 'fake-cli', 'replay.mjs'),
    onSpawn: (args) => spawned.push([...args]),
  };
}

describe('executeAttempt', () => {
  it('PRODUCT_DEFECT report → COMPLETED_WITH_RESULTS with cart-total FAILED/ASSERTION', async () => {
    const done = await executeAttempt(claim('PRODUCT_DEFECT'), deps());
    assert.equal(done.status, 'COMPLETED_WITH_RESULTS');
    assert.equal(done.workerError, null);
    const cart = done.caseResults.find((c) => c.caseKey === 'cart-total');
    assert.deepEqual([cart?.status, cart?.errorClass], ['FAILED', 'ASSERTION']);
    assert.equal(done.caseResults.length, 6);
  });

  it('SELECTOR_DRIFT and TIMEOUT reports keep their error classes', async () => {
    const drift = await executeAttempt(claim('SELECTOR_DRIFT'), deps());
    assert.ok(drift.caseResults.some((c) => c.errorClass === 'SELECTOR_NOT_FOUND'));
    const timeout = await executeAttempt(claim('TIMEOUT'), deps());
    assert.ok(
      timeout.caseResults.some((c) => c.status === 'TIMED_OUT' && c.errorClass === 'TEST_TIMEOUT'),
    );
  });

  it('PRD-07 TG_FAULT=browser_launch: a real launch failure is INFRA_ERROR / BROWSER_LAUNCH', async () => {
    const done = await executeAttempt(claim('PASS'), { ...deps(), browserLaunchFault: true });
    assert.equal(done.status, 'INFRA_ERROR');
    assert.equal(done.workerError?.errorClass, 'BROWSER_LAUNCH');
    assert.match(done.workerError?.message ?? '', /Executable doesn't exist at \/nonexistent\//);
    assert.deepEqual(done.caseResults, []);
    // Without the fault the same claim runs normally.
    const normal = await executeAttempt(claim('PRODUCT_DEFECT'), deps());
    assert.equal(normal.status, 'COMPLETED_WITH_RESULTS');
  });

  it('a crash without a report is INFRA_ERROR / UNKNOWN, never a pass', async () => {
    const done = await executeAttempt(claim('PASS', 1), deps());
    assert.equal(done.status, 'INFRA_ERROR');
    assert.equal(done.workerError?.errorClass, 'UNKNOWN');
    assert.match(done.workerError?.message ?? '', /code 139/);
  });

  it('a truncated report is INFRA_ERROR / UNKNOWN', async () => {
    const done = await executeAttempt(claim('PRODUCT_DEFECT', 2), deps());
    assert.equal(done.status, 'INFRA_ERROR');
    assert.match(done.workerError?.message ?? '', /not valid JSON/);
  });

  it('an attempt over the suite timeout is TIMED_OUT and the process is gone', async () => {
    const hung = { ...claim('PASS', 3), timeoutMs: 1000 };
    const done = await executeAttempt(hung, deps());
    assert.equal(done.status, 'TIMED_OUT');
    assert.deepEqual(done.caseResults, []);
  });

  it('a worker shutdown during the run reports INFRA_ERROR / WORKER_SHUTDOWN', async () => {
    const controller = new AbortController();
    setTimeout(() => controller.abort('shutdown'), 300);
    const done = await executeAttempt(claim('PASS', 3), deps(), controller.signal);
    assert.equal(done.status, 'INFRA_ERROR');
    assert.equal(done.workerError?.errorClass, 'WORKER_SHUTDOWN');
  });

  it('a revision mismatch runs nothing and reports CONFIG', async () => {
    const spawned: string[][] = [];
    const stale = {
      ...claim('PASS'),
      suite: { suiteKey: 'checkout-smoke', revision: '000000000000' },
    };
    const done = await executeAttempt(stale, deps(spawned));
    assert.equal(done.status, 'INFRA_ERROR');
    assert.equal(done.workerError?.errorClass, 'CONFIG');
    assert.equal(spawned.length, 0);
  });

  it('an unknown case key in the claim runs nothing', async () => {
    const spawned: string[][] = [];
    const done = await executeAttempt({ ...claim('PASS'), caseKeys: ['nope'] }, deps(spawned));
    assert.equal(done.workerError?.errorClass, 'CONFIG');
    assert.equal(spawned.length, 0);
  });

  it('removes its temporary folder afterwards', async () => {
    const spawned: string[][] = [];
    await executeAttempt(claim('PRODUCT_DEFECT'), deps(spawned));
    const output = spawned[0]?.find((a) => a.startsWith('--output='))?.slice('--output='.length);
    assert.ok(output);
    assert.equal(existsSync(path.dirname(output)), false);
  });
});

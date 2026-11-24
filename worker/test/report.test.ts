import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { mapReport, parseReport, ReportError } from '../src/report.ts';

const CHECKOUT = [
  'add-to-cart',
  'apply-coupon',
  'browse-catalog',
  'card-declined',
  'cart-total',
  'checkout-happy-path',
];

function sample(name: string): string {
  return readFileSync(
    path.join(import.meta.dirname, 'fixtures', 'playwright-1.65.1', name),
    'utf8',
  );
}

// Where the samples were recorded (their stacks say /repo/worker/suites/…).
const RECORDED_ROOT = '/repo/worker';

function mapped(mode: string, engine = 'chromium', keys = CHECKOUT) {
  return mapReport(
    parseReport(sample(`checkout-smoke-chromium-${mode}.json`)),
    engine,
    keys,
    RECORDED_ROOT,
  );
}

function byKey(mode: string) {
  return new Map(mapped(mode).cases.map((c) => [c.caseKey, c]));
}

describe('Playwright JSON report (recorded 1.65.1 samples)', () => {
  it('PRODUCT_DEFECT: only @cart-total fails, as ASSERTION, after one retry', () => {
    const cases = byKey('product-defect');
    const cart = cases.get('cart-total');
    assert.equal(cart?.status, 'FAILED');
    assert.equal(cart?.errorClass, 'ASSERTION');
    assert.equal(cart?.retryCount, 1);
    assert.match(cart?.errorMessage ?? '', /^Error: expect\(locator\)\.toHaveText/);
    assert.match(cart?.errorMessage ?? '', /Received: "\$82\.99"/);
    // eslint-disable-next-line no-control-regex
    assert.doesNotMatch(cart?.errorMessage ?? '', /\u001b/);
    for (const [key, c] of cases) {
      if (key !== 'cart-total') assert.equal(c.status, 'PASSED', key);
    }
  });

  it('PRD-07: sends the spec frame of each failure, without line numbers, and none for passes', () => {
    const defect = byKey('product-defect');
    assert.deepEqual(defect.get('cart-total')?.errorFrames, [
      'suites/checkout-smoke.spec.ts:<anonymous>',
    ]);
    for (const [key, c] of defect) {
      if (key !== 'cart-total') assert.deepEqual(c.errorFrames, [], key);
    }
    assert.deepEqual(byKey('selector-drift').get('checkout-happy-path')?.errorFrames, [
      'suites/checkout-smoke.spec.ts:<anonymous>',
    ]);
    // A test timeout has no stack of its own.
    const timedOut = [...byKey('timeout').values()].filter((c) => c.status === 'TIMED_OUT');
    assert.ok(timedOut.length > 0);
    assert.ok(timedOut.every((c) => c.errorFrames.length === 0));
    // Recorded elsewhere, the same stack is outside this root: no frame rather than a wrong one.
    const elsewhere = mapReport(
      parseReport(sample('checkout-smoke-chromium-product-defect.json')),
      'chromium',
      CHECKOUT,
      '/app/worker',
    );
    assert.ok(elsewhere.cases.every((c) => c.errorFrames.length === 0));
  });

  it('SELECTOR_DRIFT: the checkout cases fail as SELECTOR_NOT_FOUND', () => {
    const cases = byKey('selector-drift');
    for (const key of ['checkout-happy-path', 'card-declined']) {
      assert.equal(cases.get(key)?.status, 'FAILED');
      assert.equal(cases.get(key)?.errorClass, 'SELECTOR_NOT_FOUND');
    }
    assert.equal([...cases.values()].filter((c) => c.status === 'PASSED').length, 4);
  });

  it('TIMEOUT: hung orders are TIMED_OUT / TEST_TIMEOUT with both tries counted', () => {
    const c = byKey('timeout').get('checkout-happy-path');
    assert.equal(c?.status, 'TIMED_OUT');
    assert.equal(c?.errorClass, 'TEST_TIMEOUT');
    assert.equal(c?.retryCount, 1);
    assert.ok((c?.durationMs ?? 0) >= 40_000);
  });

  it('AUTOMATION_FLAKE (seed 0): passed on retry is FLAKY with the first failure kept', () => {
    const c = byKey('automation-flake').get('checkout-happy-path');
    assert.equal(c?.status, 'FLAKY');
    assert.equal(c?.retryCount, 1);
    assert.equal(c?.errorClass, 'ASSERTION');
  });

  it('durations are whole milliseconds summed over tries', () => {
    for (const c of mapped('timeout').cases) {
      assert.ok(Number.isInteger(c.durationMs) && c.durationMs >= 0);
    }
  });

  it('claimed cases the report lacks are SKIPPED and listed as missing', () => {
    const r = mapped('product-defect', 'chromium', ['cart-total', 'not-in-report']);
    assert.deepEqual(r.missing, ['not-in-report']);
    assert.deepEqual(
      r.cases.map((c) => [c.caseKey, c.status]),
      [
        ['cart-total', 'FAILED'],
        ['not-in-report', 'SKIPPED'],
      ],
    );
  });

  it('ignores tests of another project and unclaimed tags', () => {
    const r = mapped('product-defect', 'firefox');
    assert.ok(r.cases.every((c) => c.status === 'SKIPPED'));
    const one = mapped('product-defect', 'chromium', ['browse-catalog']);
    assert.equal(one.cases.length, 1);
  });

  it('rejects truncated, empty and foreign JSON', () => {
    assert.throws(() => parseReport(sample('truncated.json')), ReportError);
    assert.throws(() => parseReport(''), ReportError);
    assert.throws(() => parseReport('{"suites": "nope"}'), /unexpected shape/);
    assert.throws(() => parseReport('[]'), ReportError);
  });

  it('rejects a report that lists one case twice', () => {
    const report = parseReport(sample('checkout-smoke-chromium-product-defect.json'));
    report.suites.push(...report.suites);
    assert.throws(() => mapReport(report, 'chromium', CHECKOUT, RECORDED_ROOT), /appears twice/);
  });

  it('PRD-06: keeps the screenshot and trace of the failing try, and nothing for passing cases', () => {
    const { evidence } = mapped('product-defect');
    assert.deepEqual(
      evidence.map((e) => e.caseKey),
      ['cart-total'],
    );
    assert.match(evidence[0]?.screenshot ?? '', /test-failed-1\.png$/);
    assert.match(evidence[0]?.trace ?? '', /trace\.zip$/);
    // The error-context.md attachment (an ARIA snapshot of the page) is never collected.
    assert.ok(!JSON.stringify(evidence).includes('error-context'));
  });

  it("PRD-06: reports each case start as an offset from the first case, from Playwright's clock", () => {
    const cases = mapped('product-defect').cases;
    const offsets = cases.map((c) => c.startOffsetMs);
    assert.ok(
      offsets.every((o) => o !== null && o >= 0),
      JSON.stringify(offsets),
    );
    assert.equal(Math.min(...(offsets as number[])), 0);
    // Claimed cases the report never mentions have no start.
    const extra = mapReport(
      parseReport(sample('checkout-smoke-chromium-product-defect.json')),
      'chromium',
      [...CHECKOUT, 'not-in-report'],
      RECORDED_ROOT,
    ).cases.find((c) => c.caseKey === 'not-in-report');
    assert.equal(extra?.startOffsetMs, null);
  });
});

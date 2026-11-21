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

function mapped(mode: string, engine = 'chromium', keys = CHECKOUT) {
  return mapReport(parseReport(sample(`checkout-smoke-chromium-${mode}.json`)), engine, keys);
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
    assert.throws(() => mapReport(report, 'chromium', CHECKOUT), /appears twice/);
  });
});

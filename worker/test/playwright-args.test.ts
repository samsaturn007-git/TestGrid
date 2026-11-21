import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { loadManifest, suiteByKey } from '../src/manifest.ts';
import { ArgumentError, buildPlaywrightArgs, grepFor } from '../src/playwright-args.ts';

const suite = suiteByKey(loadManifest(), 'checkout-smoke').suite;
const base = {
  suite,
  engine: 'chromium',
  caseKeys: ['cart-total', 'add-to-cart'],
  configPath: '/app/worker/playwright.config.ts',
  outputDir: '/tmp/tg-attempt-x/out',
};

describe('buildPlaywrightArgs', () => {
  it('builds a typed argv from manifest data only', () => {
    assert.deepEqual(buildPlaywrightArgs(base), [
      'test',
      '--config=/app/worker/playwright.config.ts',
      '--project=chromium',
      '--reporter=json',
      '--output=/tmp/tg-attempt-x/out',
      '--retries=1',
      '--grep=(^|\\s)(@cart-total|@add-to-cart)(\\s|$)',
      'suites/checkout-smoke\\.spec\\.ts$',
    ]);
  });

  it('rejects an engine the suite does not allow', () => {
    const chromiumOnly = { ...suite, allowedEngines: ['chromium' as const] };
    assert.throws(
      () => buildPlaywrightArgs({ ...base, suite: chromiumOnly, engine: 'webkit' }),
      ArgumentError,
    );
    assert.throws(() => buildPlaywrightArgs({ ...base, engine: 'chrome' }), ArgumentError);
  });

  it('rejects case keys that are not in the manifest, including injection attempts', () => {
    for (const key of [
      'nope',
      '.*',
      'cart-total|.*',
      '--headed',
      '--grep=.*',
      'cart-total; rm -rf /',
      '$(id)',
    ]) {
      assert.throws(() => buildPlaywrightArgs({ ...base, caseKeys: [key] }), ArgumentError, key);
    }
    assert.throws(() => buildPlaywrightArgs({ ...base, caseKeys: [] }), ArgumentError);
    assert.throws(
      () => buildPlaywrightArgs({ ...base, caseKeys: ['cart-total', 'cart-total'] }),
      ArgumentError,
    );
  });

  it('rejects spec paths and directories the manifest schema would not allow', () => {
    const evil = { ...suite, specFiles: ['suites/../../etc/passwd'] };
    assert.throws(() => buildPlaywrightArgs({ ...base, suite: evil }), ArgumentError);
    assert.throws(() => buildPlaywrightArgs({ ...base, outputDir: 'relative/out' }), ArgumentError);
  });

  it('the grep selects exactly the claimed tags and nothing that merely starts with one', () => {
    const re = new RegExp(grepFor(['@cart-total']));
    assert.ok(re.test('cart total includes the fixed 8% tax @cart-total'));
    assert.ok(!re.test('something @cart-total-v2'));
    assert.ok(!re.test('something @x-cart-total'));
    const unicode = new RegExp(grepFor(['@cart-total']), 'u');
    assert.ok(unicode.test('t @cart-total'));
  });
});

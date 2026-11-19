import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { parseFixtureEnv } from '../suites/support/fixture-env.ts';

describe('parseFixtureEnv', () => {
  it('defaults to PASS, seed 42 and the loopback shop', () => {
    assert.deepEqual(parseFixtureEnv({}), {
      mode: 'PASS',
      seed: 42,
      baseUrl: 'http://127.0.0.1:4173',
    });
  });

  it('accepts every canonical fixture mode', () => {
    for (const mode of [
      'PASS',
      'PRODUCT_DEFECT',
      'AUTOMATION_FLAKE',
      'SELECTOR_DRIFT',
      'TIMEOUT',
    ]) {
      assert.equal(parseFixtureEnv({ FIXTURE_MODE: mode }).mode, mode);
    }
  });

  it('rejects non-canonical modes and spellings', () => {
    for (const mode of ['pass', 'TIMED_OUT', 'BROWSER_LAUNCH', '']) {
      assert.throws(() => parseFixtureEnv({ FIXTURE_MODE: mode }), /FIXTURE_MODE/);
    }
  });

  it('rejects malformed seeds', () => {
    for (const seed of ['-1', '1.5', 'abc', '1e3', '9999999999']) {
      assert.throws(() => parseFixtureEnv({ FIXTURE_SEED: seed }), /FIXTURE_SEED/);
    }
  });

  it('only targets allowlisted shop origins', () => {
    assert.equal(
      parseFixtureEnv({ DEMO_SHOP_INTERNAL: 'http://demo-shop:4173' }).baseUrl,
      'http://demo-shop:4173',
    );
    for (const url of [
      'http://example.com',
      'http://169.254.169.254',
      'file:///etc/passwd',
      'http://user:pw@localhost:4173',
      'http://localhost:4173/admin',
    ]) {
      assert.throws(() => parseFixtureEnv({ DEMO_SHOP_INTERNAL: url }), /DEMO_SHOP_INTERNAL/);
    }
  });
});

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { backoffDelays, parseDuration } from '../src/backoff.ts';
import { idempotencyKey } from '../src/idempotency.ts';

function take(gen: Generator<number>, n: number): number[] {
  return Array.from({ length: n }, () => gen.next().value as number);
}

describe('backoffDelays', () => {
  it('doubles from 1 s to a 10 s cap without jitter at the midpoint', () => {
    assert.deepEqual(
      take(
        backoffDelays(() => 0.5),
        7,
      ),
      [1000, 2000, 4000, 8000, 10000, 10000, 10000],
    );
  });

  it('jitters by at most ±20 %', () => {
    assert.deepEqual(
      take(
        backoffDelays(() => 0),
        5,
      ),
      [800, 1600, 3200, 6400, 8000],
    );
    assert.deepEqual(
      take(
        backoffDelays(() => 1),
        5,
      ),
      [1200, 2400, 4800, 9600, 12000],
    );
    for (const delay of take(backoffDelays(), 50)) {
      assert.ok(delay >= 800 && delay <= 12000, String(delay));
    }
  });
});

describe('parseDuration', () => {
  it('reads units and rejects the rest', () => {
    assert.equal(parseDuration('15m'), 900_000);
    assert.equal(parseDuration('90s'), 90_000);
    assert.equal(parseDuration('1h'), 3_600_000);
    assert.equal(parseDuration('120'), 120_000);
    assert.equal(parseDuration('1500ms'), 1500);
    for (const bad of ['', '0s', '999ms', '7h', '1.5m', '-1m', '15 minutes', '1e3']) {
      assert.equal(parseDuration(bad), null, bad);
    }
  });
});

describe('idempotencyKey', () => {
  const source = {
    repo: 'owner/testgrid',
    commitSha: '0123456789abcdef',
    ciRef: 'gh:987654/1',
    suite: 'checkout-smoke',
    engines: ['firefox', 'chromium'],
    fixture: null,
  };

  it('is stable for the same job and independent of engine order', () => {
    const key = idempotencyKey(source);
    assert.match(key, /^tgcli:[0-9a-f]{64}$/);
    assert.equal(idempotencyKey({ ...source, engines: ['chromium', 'firefox'] }), key);
  });

  it('changes with every input, a re-run attempt included', () => {
    const key = idempotencyKey(source);
    for (const changed of [
      { ...source, repo: 'other/repo' },
      { ...source, commitSha: 'fedcba9876543210' },
      { ...source, ciRef: 'gh:987654/2' },
      { ...source, suite: 'catalog-regression' },
      { ...source, engines: ['chromium'] },
      { ...source, fixture: { mode: 'PASS', seed: 42 } },
    ]) {
      assert.notEqual(idempotencyKey(changed), key);
    }
  });

  it('is random without a CI ref', () => {
    assert.notEqual(
      idempotencyKey({ ...source, ciRef: null }),
      idempotencyKey({ ...source, ciRef: null }),
    );
  });
});

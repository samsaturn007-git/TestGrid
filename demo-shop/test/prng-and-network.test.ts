import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { isPrivateAddress } from '../src/server/network.ts';
import { mulberry32 } from '../src/server/prng.ts';

const draws = (seed: number, n: number) => {
  const next = mulberry32(seed);
  return Array.from({ length: n }, () => next());
};

describe('mulberry32', () => {
  it('is deterministic for a seed', () => {
    assert.deepEqual(draws(42, 20), draws(42, 20));
  });

  it('differs between seeds', () => {
    assert.notDeepEqual(draws(42, 5), draws(43, 5));
  });

  it('stays in [0, 1)', () => {
    for (const x of draws(7, 10_000)) assert.ok(x >= 0 && x < 1);
  });

  it('produces both late and on-time tries across seeds 0..31', () => {
    const late = Array.from({ length: 32 }, (_, s) => draws(s, 1)[0]! < 0.5);
    assert.ok(late.includes(true) && late.includes(false));
  });
});

describe('isPrivateAddress', () => {
  for (const ip of [
    '127.0.0.1',
    '10.1.2.3',
    '172.16.0.1',
    '172.31.255.255',
    '192.168.1.1',
    '::1',
    '::ffff:172.18.0.1',
    'fd00::1',
    'fe80::1',
  ]) {
    it(`accepts ${ip}`, () => assert.equal(isPrivateAddress(ip), true));
  }
  for (const ip of [
    '8.8.8.8',
    '172.32.0.1',
    '172.15.0.1',
    '2001:4860::8888',
    '::ffff:8.8.8.8',
    'not-an-ip',
    undefined,
  ]) {
    it(`rejects ${String(ip)}`, () => assert.equal(isPrivateAddress(ip), false));
  }
});

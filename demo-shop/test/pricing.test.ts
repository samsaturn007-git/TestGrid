import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { CATALOG } from '../src/server/catalog.ts';
import { buildCartLines, computeTotals } from '../src/server/pricing.ts';

const cart = (entries: [string, number][]) => buildCartLines(new Map(entries));

describe('catalog', () => {
  it('seeds exactly 6 products with unique ids and slugs', () => {
    assert.equal(CATALOG.length, 6);
    assert.equal(new Set(CATALOG.map((p) => p.id)).size, 6);
    assert.equal(new Set(CATALOG.map((p) => p.slug)).size, 6);
  });
});

describe('computeTotals', () => {
  // Same cart the @cart-total smoke case builds: lamp x1 + mug x2.
  const lines = cart([
    ['p-001', 1],
    ['p-002', 2],
  ]);

  it('adds a fixed 8% tax in PASS mode', () => {
    assert.deepEqual(computeTotals(lines, null, 'PASS'), {
      subtotalCents: 7499,
      discountCents: 0,
      taxCents: 600, // 599.92 rounded half up
      totalCents: 8099,
    });
  });

  it('applies SAVE10 before tax', () => {
    assert.deepEqual(computeTotals(cart([['p-003', 1]]), 'SAVE10', 'PASS'), {
      subtotalCents: 2400,
      discountCents: 240,
      taxCents: 173, // 8% of 2160 = 172.8
      totalCents: 2333,
    });
  });

  it('PRODUCT_DEFECT skews only the total, by exactly 2.00', () => {
    const good = computeTotals(lines, null, 'PASS');
    const bad = computeTotals(lines, null, 'PRODUCT_DEFECT');
    assert.equal(bad.totalCents - good.totalCents, 200);
    assert.equal(bad.subtotalCents, good.subtotalCents);
    assert.equal(bad.taxCents, good.taxCents);
    assert.equal(bad.discountCents, good.discountCents);
  });

  it('an empty cart totals zero in every mode', () => {
    for (const mode of ['PASS', 'PRODUCT_DEFECT'] as const) {
      assert.equal(computeTotals([], null, mode).totalCents, 0);
    }
  });

  it('ignores unknown product ids', () => {
    assert.equal(cart([['nope', 3]]).length, 0);
  });
});

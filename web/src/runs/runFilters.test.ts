import { describe, expect, it } from 'vitest';
import {
  activeFilters,
  apiSearch,
  branchError,
  filtersFromSearch,
  hasFilters,
  NO_FILTERS,
  RUN_STATUSES,
  searchFromFilters,
  TRIGGERS,
  withoutFilter,
} from './runFilters.ts';

const parse = (qs: string) => filtersFromSearch(new URLSearchParams(qs));

describe('run list filters in the URL (PRD-05 FR-2, T3)', () => {
  it('lists every RunStatus and Trigger from contracts §2.1', () => {
    expect(RUN_STATUSES).toEqual([
      'QUEUED',
      'RUNNING',
      'PASSED',
      'FAILED',
      'TIMED_OUT',
      'INFRA_ERROR',
      'CANCELLED',
    ]);
    expect(TRIGGERS).toEqual(['MANUAL', 'CI', 'RERUN', 'BENCHMARK', 'CHAOS']);
  });

  it('reads repeated and comma-separated values, dropping blanks and repeats', () => {
    expect(parse('status=FAILED&status=TIMED_OUT,FAILED&status=&trigger=CI')).toEqual({
      statuses: ['FAILED', 'TIMED_OUT'],
      suite: null,
      branch: null,
      triggers: ['CI'],
    });
  });

  it('treats empty single values as no filter and trims them', () => {
    expect(parse('suite=&branch=%20main%20')).toEqual({ ...NO_FILTERS, branch: 'main' });
  });

  it('keeps values it does not know, so the API can reject them instead of them vanishing', () => {
    expect(parse('status=GREEN').statuses).toEqual(['GREEN']);
  });

  it('writes the same filters as the same URL, and reads it back unchanged', () => {
    const filters = parse('trigger=CI&branch=main&status=FAILED&suite=checkout-smoke');
    const url = searchFromFilters(filters).toString();
    expect(url).toBe('status=FAILED&suite=checkout-smoke&branch=main&trigger=CI');
    expect(parse(url)).toEqual(filters);
    expect(searchFromFilters(NO_FILTERS).toString()).toBe('');
  });

  it('builds the API query: suite becomes suiteKey, values are encoded, the cursor is passed on', () => {
    const filters = parse(
      'status=FAILED&status=TIMED_OUT&suite=checkout-smoke&branch=feat/a&trigger=CI',
    );
    const query = new URLSearchParams(apiSearch(filters, 'abc', 10));
    expect([...query.entries()]).toEqual([
      ['limit', '10'],
      ['status', 'FAILED'],
      ['status', 'TIMED_OUT'],
      ['suiteKey', 'checkout-smoke'],
      ['branch', 'feat/a'],
      ['trigger', 'CI'],
      ['cursor', 'abc'],
    ]);
    expect(apiSearch(NO_FILTERS)).toBe('limit=25');
    // A value with & or = cannot add a parameter of its own.
    expect(
      new URLSearchParams(apiSearch({ ...NO_FILTERS, branch: 'a&status=PASSED' })).getAll('status'),
    ).toEqual([]);
  });

  it('lists active filters as removable chips and removes exactly one', () => {
    const filters = parse('status=FAILED&status=TIMED_OUT&suite=checkout-smoke&trigger=CI');
    expect(activeFilters(filters)).toEqual([
      { key: 'status', value: 'FAILED' },
      { key: 'status', value: 'TIMED_OUT' },
      { key: 'suite', value: 'checkout-smoke' },
      { key: 'trigger', value: 'CI' },
    ]);
    expect(withoutFilter(filters, 'status', 'FAILED').statuses).toEqual(['TIMED_OUT']);
    expect(withoutFilter(filters, 'suite', 'checkout-smoke').suite).toBeNull();
    expect(hasFilters(filters)).toBe(true);
    expect(hasFilters(NO_FILTERS)).toBe(false);
  });

  it.each(['', 'main', 'feat/coupon-rounding', 'release-1.2'])('accepts branch %j', (b) => {
    expect(branchError(b)).toBeNull();
  });

  it.each(['a..b', 'gh:main', 'has space'])('refuses branch %j', (b) => {
    expect(branchError(b)).toMatch(/letters, digits/);
  });
});

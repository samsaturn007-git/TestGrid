import { describe, expect, it } from 'vitest';
import { CHECKOUT_CASES } from '../../test/api.ts';
import { attempt, attemptResults, result, results, run, target } from '../../test/runFixtures.ts';
import { buildMatrix, failedTargets, sortCases, summarySentence, tally } from './model.ts';

describe('run detail model (PRD-05 FR-2, T4)', () => {
  describe('browser matrix', () => {
    it('has one row per suite case in caseKey order and one column per target', () => {
      const r = run();
      const { columns, rows } = buildMatrix(r, results(r, []), CHECKOUT_CASES);
      expect(columns.map((c) => c.target.engine)).toEqual(['chromium', 'firefox']);
      expect(rows.map((row) => row.caseKey)).toEqual(CHECKOUT_CASES.map((c) => c.caseKey).sort());
      expect(rows.find((row) => row.caseKey === 'cart-total')?.title).toBe(
        'Cart total matches its lines',
      );
    });

    it('shows the case result from each target’s latest attempt', () => {
      const r = run();
      const res = results(r, [
        [attemptResults(attempt(1, 'FAILED'), [result('cart-total', 'FAILED', 900)])],
        [attemptResults(attempt(1, 'PASSED'), [result('cart-total', 'PASSED', 800)])],
      ]);
      const row = buildMatrix(r, res, CHECKOUT_CASES).rows.find((x) => x.caseKey === 'cart-total');
      expect(row?.cells).toEqual([
        { kind: 'case', result: result('cart-total', 'FAILED', 900) },
        { kind: 'case', result: result('cart-total', 'PASSED', 800) },
      ]);
    });

    it('never shows an older attempt’s result while a newer attempt has none yet', () => {
      // Attempt 1 FAILED; attempt 2 is RUNNING with no results: the cell must say RUNNING.
      const r = run({
        status: 'RUNNING',
        finishedAt: null,
        targets: [target('chromium', 'RUNNING')],
      });
      const res = results(r, [
        [
          attemptResults(attempt(2, 'RUNNING')),
          attemptResults(attempt(1, 'ABANDONED'), [result('cart-total', 'FAILED')]),
        ],
      ]);
      const row = buildMatrix(r, res, CHECKOUT_CASES).rows.find((x) => x.caseKey === 'cart-total');
      expect(row?.cells).toEqual([{ kind: 'target', status: 'RUNNING' }]);
    });

    it('shows the target state for queued targets and for targets that ended without results', () => {
      const r = run({
        targets: [target('chromium', 'INFRA_ERROR'), target('webkit', 'QUEUED')],
      });
      const row = buildMatrix(r, results(r, []), CHECKOUT_CASES).rows[0];
      expect(row?.cells).toEqual([
        { kind: 'target', status: 'INFRA_ERROR' },
        { kind: 'target', status: 'QUEUED' },
      ]);
    });

    it('keeps a result whose case is not in the suite list, so nothing is dropped', () => {
      const r = run({ targets: [target('chromium', 'PASSED')] });
      const res = results(r, [
        [attemptResults(attempt(1, 'PASSED'), [result('legacy-case', 'PASSED')])],
      ]);
      const rows = buildMatrix(r, res, CHECKOUT_CASES).rows;
      expect(rows.find((x) => x.caseKey === 'legacy-case')).toMatchObject({ title: null });
    });
  });

  describe('summary', () => {
    it('counts targets by status and case results from latest attempts only', () => {
      const r = run({
        targets: [
          target('chromium', 'FAILED'),
          target('firefox', 'FAILED'),
          target('webkit', 'PASSED'),
        ],
      });
      const res = results(r, [
        [
          attemptResults(attempt(2, 'FAILED'), [result('a', 'FAILED'), result('b', 'PASSED')]),
          attemptResults(attempt(1, 'FAILED'), [result('a', 'FAILED'), result('b', 'FAILED')]),
        ],
        [attemptResults(attempt(1, 'FAILED'), [result('a', 'FAILED'), result('b', 'PASSED')])],
        [attemptResults(attempt(1, 'PASSED'), [result('a', 'PASSED'), result('b', 'PASSED')])],
      ]);
      expect(summarySentence(tally(r, res))).toBe(
        '3 targets: 2 FAILED, 1 PASSED. 4 of 6 case results PASSED.',
      );
    });

    it('says when there are no case results yet', () => {
      const r = run({ status: 'QUEUED', targets: [target('chromium', 'QUEUED')] });
      expect(summarySentence(tally(r, results(r, [])))).toBe(
        '1 target: 1 QUEUED. No case results yet.',
      );
    });
  });

  it('reruns FAILED, TIMED_OUT and INFRA_ERROR targets, never PASSED or CANCELLED', () => {
    const r = run({
      targets: [
        target('chromium', 'FAILED'),
        target('firefox', 'CANCELLED'),
        target('webkit', 'TIMED_OUT'),
      ],
    });
    expect(failedTargets(r).map((t) => t.engine)).toEqual(['chromium', 'webkit']);
    expect(failedTargets(run({ targets: [target('chromium', 'INFRA_ERROR')] }))).toHaveLength(1);
    expect(failedTargets(run({ targets: [target('chromium', 'PASSED')] }))).toEqual([]);
  });

  describe('case sorting', () => {
    const rows = [
      result('b-pass', 'PASSED', 300),
      result('a-fail', 'FAILED', 100),
      result('c-flaky', 'FLAKY', 900),
      result('d-timeout', 'TIMED_OUT', 5_000),
      result('e-pass-slow', 'PASSED', 4_000),
    ];

    it('puts failures first, slowest first within a status', () => {
      expect(
        sortCases(rows, { by: 'status', direction: 'ascending' }).map((r) => r.caseKey),
      ).toEqual(['a-fail', 'd-timeout', 'c-flaky', 'e-pass-slow', 'b-pass']);
    });

    it('reverses status order on descending', () => {
      expect(sortCases(rows, { by: 'status', direction: 'descending' })[0]?.status).toBe('PASSED');
    });

    it('sorts by duration either way', () => {
      expect(
        sortCases(rows, { by: 'duration', direction: 'descending' }).map((r) => r.durationMs),
      ).toEqual([5_000, 4_000, 900, 300, 100]);
      expect(
        sortCases(rows, { by: 'duration', direction: 'ascending' }).map((r) => r.durationMs),
      ).toEqual([100, 300, 900, 4_000, 5_000]);
    });

    it('does not change the input', () => {
      const copy = [...rows];
      sortCases(rows, { by: 'duration', direction: 'ascending' });
      expect(rows).toEqual(copy);
    });
  });
});

import type {
  AttemptResults,
  CaseResult,
  CaseStatus,
  RunResponse,
  RunResults,
  RunTarget,
  TargetStatus,
  TestCase,
} from '../../api/types.ts';

// Pure view model for the run detail page (PRD-05 FR-2). Every value here is read from the API
// responses; nothing is guessed. A case without a result in the latest attempt shows its
// target's state instead, so an unfinished or failed-before-results target is never painted as
// a case outcome.

export interface MatrixColumn {
  target: RunTarget;
  /** The newest attempt with its case results, or null before the first claim. */
  latest: AttemptResults | null;
}

export type MatrixCell =
  { kind: 'case'; result: CaseResult } | { kind: 'target'; status: TargetStatus };

export interface MatrixRow {
  caseKey: string;
  title: string | null;
  cells: MatrixCell[];
}

export function latestAttempt(results: RunResults | undefined, targetId: string) {
  // The API lists attempts newest first (contracts §4).
  return results?.targets.find((t) => t.id === targetId)?.attempts[0] ?? null;
}

/**
 * Cases × targets. Rows are the suite's cases in caseKey order, plus any case key a result
 * names that the suite list doesn't (an older revision), so no result is dropped.
 */
export function buildMatrix(
  run: RunResponse,
  results: RunResults | undefined,
  cases: TestCase[] | undefined,
): { columns: MatrixColumn[]; rows: MatrixRow[] } {
  const columns = run.targets.map((target) => ({
    target,
    latest: latestAttempt(results, target.id),
  }));
  const titles = new Map((cases ?? []).map((c) => [c.caseKey, c.title]));
  const keys = new Set(titles.keys());
  for (const column of columns) {
    for (const result of column.latest?.caseResults ?? []) keys.add(result.caseKey);
  }
  const rows = [...keys].sort().map((caseKey) => ({
    caseKey,
    title: titles.get(caseKey) ?? null,
    cells: columns.map(({ target, latest }): MatrixCell => {
      const result = latest?.caseResults.find((r) => r.caseKey === caseKey);
      return result ? { kind: 'case', result } : { kind: 'target', status: target.status };
    }),
  }));
  return { columns, rows };
}

export interface RunTally {
  targets: number;
  targetsByStatus: Partial<Record<TargetStatus, number>>;
  caseResults: number;
  casesByStatus: Partial<Record<CaseStatus, number>>;
}

/** Counts for the header sentence, from targets and their latest attempts only. */
export function tally(run: RunResponse, results: RunResults | undefined): RunTally {
  const targetsByStatus: RunTally['targetsByStatus'] = {};
  for (const t of run.targets) targetsByStatus[t.status] = (targetsByStatus[t.status] ?? 0) + 1;
  const casesByStatus: RunTally['casesByStatus'] = {};
  let caseResults = 0;
  for (const t of run.targets) {
    for (const r of latestAttempt(results, t.id)?.caseResults ?? []) {
      casesByStatus[r.status] = (casesByStatus[r.status] ?? 0) + 1;
      caseResults += 1;
    }
  }
  return { targets: run.targets.length, targetsByStatus, caseResults, casesByStatus };
}

const TARGET_ORDER: TargetStatus[] = [
  'FAILED',
  'TIMED_OUT',
  'INFRA_ERROR',
  'CANCELLED',
  'RUNNING',
  'CLAIMED',
  'QUEUED',
  'PASSED',
];

/** "3 targets: 2 FAILED, 1 PASSED. 15 of 18 case results PASSED." */
export function summarySentence(t: RunTally): string {
  const parts = TARGET_ORDER.filter((s) => t.targetsByStatus[s]).map(
    (s) => `${t.targetsByStatus[s]} ${s}`,
  );
  const targets =
    t.targets === 0
      ? 'No targets.'
      : `${t.targets} ${t.targets === 1 ? 'target' : 'targets'}: ${parts.join(', ')}.`;
  if (t.caseResults === 0) return `${targets} No case results yet.`;
  return `${targets} ${t.casesByStatus.PASSED ?? 0} of ${t.caseResults} case results PASSED.`;
}

/** What FAILED_TARGETS would rerun (the API's rule: FAILED, TIMED_OUT, INFRA_ERROR). */
export function failedTargets(run: RunResponse): RunTarget[] {
  return run.targets.filter(
    (t) => t.status === 'FAILED' || t.status === 'TIMED_OUT' || t.status === 'INFRA_ERROR',
  );
}

export type CaseSort = { by: 'status' | 'duration'; direction: 'ascending' | 'descending' };

/** Failures first when sorting by status ascending. */
const CASE_RANK: Record<CaseStatus, number> = {
  FAILED: 0,
  TIMED_OUT: 1,
  FLAKY: 2,
  SKIPPED: 3,
  PASSED: 4,
};

/** Sorted by the chosen column; ties fall back to the other column, then caseKey. */
export function sortCases(results: CaseResult[], sort: CaseSort): CaseResult[] {
  const sign = sort.direction === 'ascending' ? 1 : -1;
  const byStatus = (a: CaseResult, b: CaseResult) => CASE_RANK[a.status] - CASE_RANK[b.status];
  const byDuration = (a: CaseResult, b: CaseResult) => a.durationMs - b.durationMs;
  return [...results].sort((a, b) => {
    const primary = sort.by === 'status' ? byStatus(a, b) : byDuration(a, b);
    if (primary !== 0) return sign * primary;
    const secondary = sort.by === 'status' ? byDuration(b, a) : byStatus(a, b);
    return secondary !== 0 ? secondary : a.caseKey.localeCompare(b.caseKey);
  });
}

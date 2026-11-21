import type { operations } from '@testgrid/contracts';
import type { RunStatus, RunSummary } from '../api/types.ts';

// The run list's filters live in the address bar (PRD-05 FR-2), so a copied URL opens the same
// view. This module is the one translation between the URL, the filter state and the API query.

export type Trigger = RunSummary['trigger'];
export type RunListQuery = NonNullable<operations['listRuns']['parameters']['query']>;

// Record<Enum, true> makes the compiler reject a missing or misspelt value.
const STATUS_SET: Record<RunStatus, true> = {
  QUEUED: true,
  RUNNING: true,
  PASSED: true,
  FAILED: true,
  TIMED_OUT: true,
  INFRA_ERROR: true,
  CANCELLED: true,
};
const TRIGGER_SET: Record<Trigger, true> = {
  MANUAL: true,
  CI: true,
  RERUN: true,
  BENCHMARK: true,
  CHAOS: true,
};
export const RUN_STATUSES = Object.keys(STATUS_SET) as RunStatus[];
export const TRIGGERS = Object.keys(TRIGGER_SET) as Trigger[];

/**
 * Values are kept as the URL spelled them, even unknown ones: the API rejects those with a
 * 400 the page shows, instead of the page quietly dropping part of a shared link.
 */
export interface RunFilters {
  statuses: string[];
  suite: string | null;
  branch: string | null;
  triggers: string[];
}

export const NO_FILTERS: RunFilters = { statuses: [], suite: null, branch: null, triggers: [] };

/** URL parameter names. `suite` reads better in a shared link than the API's `suiteKey`. */
const PARAM = { status: 'status', suite: 'suite', branch: 'branch', trigger: 'trigger' } as const;

function many(params: URLSearchParams, name: string): string[] {
  // Accept both ?status=A&status=B and ?status=A,B; drop blanks and repeats, keep order.
  const values = params.getAll(name).flatMap((v) => v.split(','));
  return [...new Set(values.map((v) => v.trim()).filter((v) => v !== ''))];
}

function one(params: URLSearchParams, name: string): string | null {
  const value = params.get(name)?.trim() ?? '';
  return value === '' ? null : value;
}

export function filtersFromSearch(params: URLSearchParams): RunFilters {
  return {
    statuses: many(params, PARAM.status),
    suite: one(params, PARAM.suite),
    branch: one(params, PARAM.branch),
    triggers: many(params, PARAM.trigger),
  };
}

/** The address-bar form. Canonical order, so the same filters always make the same URL. */
export function searchFromFilters(filters: RunFilters): URLSearchParams {
  const params = new URLSearchParams();
  for (const s of filters.statuses) params.append(PARAM.status, s);
  if (filters.suite) params.set(PARAM.suite, filters.suite);
  if (filters.branch) params.set(PARAM.branch, filters.branch);
  for (const t of filters.triggers) params.append(PARAM.trigger, t);
  return params;
}

/** The API query string for one page (`cursor` from the previous page's nextCursor). */
export function apiSearch(filters: RunFilters, cursor?: string | null, limit = 25): string {
  const params = new URLSearchParams({ limit: String(limit) });
  for (const s of filters.statuses) params.append('status', s);
  if (filters.suite) params.set('suiteKey', filters.suite);
  if (filters.branch) params.set('branch', filters.branch);
  for (const t of filters.triggers) params.append('trigger', t);
  if (cursor) params.set('cursor', cursor);
  return params.toString();
}

export function hasFilters(filters: RunFilters): boolean {
  return (
    filters.statuses.length > 0 ||
    filters.suite !== null ||
    filters.branch !== null ||
    filters.triggers.length > 0
  );
}

export type FilterKey = 'status' | 'suite' | 'branch' | 'trigger';

/** One removable chip per active value. */
export function activeFilters(filters: RunFilters): { key: FilterKey; value: string }[] {
  return [
    ...filters.statuses.map((value) => ({ key: 'status' as const, value })),
    ...(filters.suite ? [{ key: 'suite' as const, value: filters.suite }] : []),
    ...(filters.branch ? [{ key: 'branch' as const, value: filters.branch }] : []),
    ...filters.triggers.map((value) => ({ key: 'trigger' as const, value })),
  ];
}

export function withoutFilter(filters: RunFilters, key: FilterKey, value: string): RunFilters {
  switch (key) {
    case 'status':
      return { ...filters, statuses: filters.statuses.filter((v) => v !== value) };
    case 'trigger':
      return { ...filters, triggers: filters.triggers.filter((v) => v !== value) };
    case 'suite':
      return { ...filters, suite: null };
    case 'branch':
      return { ...filters, branch: null };
  }
}

// Same rule as the API (contracts §4), checked before the branch goes into the URL.
const BRANCH = /^(?!.*\.\.)[A-Za-z0-9._/-]{1,200}$/;
export function branchError(branch: string): string | null {
  const value = branch.trim();
  if (value === '' || BRANCH.test(value)) return null;
  return 'Use letters, digits and . _ / - only, with no "..".';
}

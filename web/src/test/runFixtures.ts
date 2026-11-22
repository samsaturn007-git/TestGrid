import type {
  AttemptResults,
  AttemptSummary,
  CaseResult,
  RunResponse,
  RunResults,
  RunTarget,
} from '../api/types.ts';
import { CHECKOUT, PROJECT_ID } from './api.ts';

// Run detail fixtures for component tests (mocked, not live). Times are fixed around
// 2026-11-22T12:00:00Z so rendered clocks are predictable.

export const T0 = '2026-11-22T11:50:00.000Z';
export const T1 = '2026-11-22T11:50:30.500Z';
export const T2 = '2026-11-22T11:51:12.250Z';

export function target(
  engine: RunTarget['engine'],
  status: RunTarget['status'],
  overrides: Partial<RunTarget> = {},
): RunTarget {
  const terminal = !['QUEUED', 'CLAIMED', 'RUNNING'].includes(status);
  return {
    id: `0000000${engine.length}-0000-4000-8000-${engine
      .padEnd(12, '0')
      .slice(0, 12)
      .replace(/[^0-9a-f]/g, 'a')}`,
    targetKey: `local:${engine}:0`,
    executor: 'LOCAL',
    engine,
    status,
    attemptCount: status === 'QUEUED' ? 0 : 1,
    maxAttempts: 3,
    queuedAt: T0,
    finishedAt: terminal ? T2 : null,
    latestAttempt: null,
    ...overrides,
  };
}

export function run(overrides: Partial<RunResponse> = {}): RunResponse {
  const id = overrides.id ?? '7c1e04b9-0000-4000-8000-000000000001';
  const targets = overrides.targets ?? [target('chromium', 'FAILED'), target('firefox', 'PASSED')];
  return {
    id,
    projectId: PROJECT_ID,
    suiteId: CHECKOUT.id,
    suiteKey: CHECKOUT.suiteKey,
    suiteRevision: CHECKOUT.revision,
    status: 'FAILED',
    trigger: 'MANUAL',
    idempotencyKey: 'k-1',
    fixture: { mode: 'PRODUCT_DEFECT', seed: 42 },
    sharding: { mode: 'NONE', shards: 1 },
    source: { branch: 'main', commitSha: '0123456789abcdef' },
    queuedAt: T0,
    startedAt: T1,
    finishedAt: T2,
    cancelRequestedAt: null,
    parentRunId: null,
    rerunIds: [],
    links: { self: `/api/v1/runs/${id}`, suite: `/api/v1/suites/${CHECKOUT.id}` },
    ...overrides,
    targets,
  };
}

export function attempt(
  no: number,
  status: AttemptSummary['status'],
  overrides: Partial<AttemptSummary> = {},
): AttemptSummary {
  const terminal = !['CLAIMED', 'RUNNING'].includes(status);
  return {
    id: `a0000000-0000-4000-8000-${String(no).padStart(12, '0')}`,
    attemptNo: no,
    workerId: 'worker-bee2be453300',
    status,
    claimedAt: T0,
    startedAt: T1,
    finishedAt: terminal ? T2 : null,
    leasedUntil: '2026-11-22T11:51:30.000Z',
    leaseExpired: false,
    lastHeartbeatAt: T1,
    errorClass: null,
    ...overrides,
  };
}

export function result(
  caseKey: string,
  status: CaseResult['status'],
  durationMs = 1_000,
  overrides: Partial<CaseResult> = {},
): CaseResult {
  return {
    caseKey,
    status,
    durationMs,
    retryCount: 0,
    errorClass: status === 'FAILED' ? 'ASSERTION' : null,
    errorMessage: status === 'FAILED' ? 'expected 42.00 to equal 40.00' : null,
    ...overrides,
  };
}

export function attemptResults(
  summary: AttemptSummary,
  caseResults: CaseResult[] = [],
  errorDetail: string | null = null,
): AttemptResults {
  return { attempt: summary, caseResults, errorDetail };
}

/** Results for a run: `attempts[targetIndex]` is that target's attempts, newest first. */
export function results(r: RunResponse, attempts: AttemptResults[][]): RunResults {
  return {
    runId: r.id,
    status: r.status,
    targets: r.targets.map((t, i) => ({
      id: t.id,
      targetKey: t.targetKey,
      engine: t.engine,
      status: t.status,
      attempts: attempts[i] ?? [],
    })),
  };
}

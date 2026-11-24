import type { Deps } from '../src/client.ts';

export interface Recorded {
  method: string;
  path: string;
  headers: Record<string, string>;
  body: unknown;
}

export type Reply = { status: number; body?: unknown; headers?: Record<string, string> } | Error;

export const PROJECT_ID = '00000000-0000-4000-8000-000000000001';
export const RUN_ID = '11111111-1111-4111-8111-111111111111';

/**
 * A fake API behind an injected fetch and a fake clock: sleeping advances `now`, so a 15-minute
 * deadline takes no real time.
 */
export function harness(
  handler: (method: string, path: string, body: unknown, n: number) => Reply,
  env: Record<string, string | undefined> = {},
) {
  const requests: Recorded[] = [];
  let clock = 1_000_000;
  let out = '';
  let err = '';
  const sleeps: number[] = [];
  const files = new Map<string, string>();
  const deps: Deps = {
    fetch: (input: string | URL | Request, init?: RequestInit) => {
      const url = new URL(input instanceof Request ? input.url : input.toString());
      const path = url.pathname.replace(/^\/api\/v1/, '') + url.search;
      const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined;
      const headers = { ...(init?.headers as Record<string, string> | undefined) };
      const method = init?.method ?? 'GET';
      requests.push({ method, path, headers, body });
      const reply = handler(method, path, body, requests.length);
      if (reply instanceof Error) {
        return Promise.reject(reply);
      }
      const problem = reply.status >= 400 && reply.status < 500 && reply.status !== 429;
      return Promise.resolve(
        new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
          status: reply.status,
          headers: {
            'Content-Type': problem ? 'application/problem+json' : 'application/json',
            ...reply.headers,
          },
        }),
      );
    },
    now: () => clock,
    sleep: (ms) => {
      sleeps.push(ms);
      clock += ms;
      return Promise.resolve();
    },
    random: () => 0.5,
    env,
    stdout: (text) => {
      out += text;
    },
    stderr: (text) => {
      err += text;
    },
    appendFile: (path, text) => {
      if (path.startsWith('/unwritable/')) {
        throw new Error('EACCES: permission denied');
      }
      files.set(path, (files.get(path) ?? '') + text);
    },
  };
  return {
    deps,
    requests,
    sleeps,
    files,
    stdout: () => out,
    stderr: () => err,
    elapsed: () => clock - 1_000_000,
  };
}

export function runBody(status: string, overrides: Record<string, unknown> = {}) {
  return {
    id: RUN_ID,
    projectId: PROJECT_ID,
    suiteId: '22222222-2222-4222-8222-222222222222',
    suiteKey: 'checkout-smoke',
    suiteRevision: 'abcdefabcdef',
    status,
    trigger: 'CI',
    targets: [
      {
        id: '33333333-3333-4333-8333-333333333333',
        targetKey: 'local:chromium:0',
        executor: 'LOCAL',
        engine: 'chromium',
        status: status === 'QUEUED' ? 'QUEUED' : status,
        attemptCount: 1,
        maxAttempts: 3,
        latestAttempt: null,
        queuedAt: '2026-11-24T00:00:00Z',
        finishedAt: null,
      },
    ],
    ...overrides,
  };
}

function rule(
  ruleId: string,
  outcome: string,
  blocking: boolean,
  evidence: unknown[] = [],
  observed: number | null = 0,
  threshold: number | null = 0,
) {
  return { ruleId, observed, threshold, outcome, blocking, evidence };
}

export function qualityBody(outcome: 'PASS' | 'FAIL' | 'INDETERMINATE' | null) {
  if (outcome === null) {
    return {
      runId: RUN_ID,
      runStatus: 'RUNNING',
      evaluationId: null,
      outcome: null,
      policy: null,
      evaluatedAt: null,
      rules: [],
    };
  }
  const cartTotal = {
    target: 'local:chromium:0',
    engine: 'chromium',
    caseKey: 'cart-total',
    status: 'FAILED',
    counted: true,
    detail: 'critical case FAILED',
  };
  const quarantined = {
    ...cartTotal,
    caseKey: 'card-declined',
    status: 'QUARANTINED_FAIL',
    counted: false,
    detail: 'FAILED; quarantined (q-1) until 2026-12-01T00:00:00Z, not counted',
  };
  return {
    runId: RUN_ID,
    runStatus: outcome === 'PASS' ? 'PASSED' : outcome === 'FAIL' ? 'FAILED' : 'INFRA_ERROR',
    evaluationId: '44444444-4444-4444-8444-444444444444',
    outcome,
    policy: { id: '55555555-5555-4555-8555-555555555555', version: 3 },
    evaluatedAt: '2026-11-24T00:05:00Z',
    rules: [
      rule(
        'required-targets',
        outcome === 'INDETERMINATE' ? 'INDETERMINATE' : 'PASS',
        true,
        outcome === 'INDETERMINATE'
          ? [
              {
                target: 'local:chromium:0',
                engine: 'chromium',
                caseKey: null,
                status: 'INFRA_ERROR',
                counted: false,
                detail: 'ended INFRA_ERROR (BROWSER_LAUNCH) without test results',
              },
            ]
          : [],
        outcome === 'INDETERMINATE' ? 0 : 1,
        1,
      ),
      rule(
        'critical-cases',
        outcome === 'FAIL' ? 'FAIL' : 'PASS',
        true,
        outcome === 'FAIL' ? [cartTotal] : [],
        outcome === 'FAIL' ? 1 : 0,
      ),
      rule(
        'max-failed-cases',
        outcome === 'FAIL' ? 'FAIL' : 'PASS',
        true,
        outcome === 'FAIL' ? [cartTotal, quarantined] : [],
        outcome === 'FAIL' ? 1 : 0,
      ),
      rule(
        'quarantine-honored',
        'PASS',
        false,
        outcome === 'FAIL' ? [quarantined] : [],
        outcome === 'FAIL' ? 1 : 0,
        null,
      ),
    ],
  };
}

export const PROJECTS = {
  items: [{ id: PROJECT_ID, slug: 'demo', name: 'Demo shop', links: { self: '/x' } }],
  nextCursor: null,
};

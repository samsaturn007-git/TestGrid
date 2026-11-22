import { vi } from 'vitest';
import type {
  PageProject,
  PageRunSummary,
  Problem,
  RunSummary,
  Suite,
  SuiteDetail,
  TestCase,
  WorkersResponse,
  WorkerView,
} from '../api/types.ts';

// A fake API for component tests (mocked, not live): routes by method and path, and records
// every request so a test can assert what the UI sent. Values mirror worker/manifest/suites.json.

export const PROJECT_ID = '00000000-0000-4000-8000-000000000001';

export const PROJECTS: PageProject = {
  items: [
    { id: PROJECT_ID, name: 'Local project', slug: 'local', links: { self: '/api/v1/projects' } },
  ],
  nextCursor: null,
};

const ALL_MODES: Suite['fixtureModes'] = [
  'PASS',
  'PRODUCT_DEFECT',
  'AUTOMATION_FLAKE',
  'SELECTOR_DRIFT',
  'TIMEOUT',
];

export function suite(overrides: Partial<Suite> & Pick<Suite, 'id' | 'suiteKey'>): Suite {
  return {
    projectId: PROJECT_ID,
    revision: '000000000000',
    displayName: overrides.suiteKey,
    enabled: true,
    current: true,
    timeoutMs: 120_000,
    playwrightRetries: 1,
    allowedEngines: ['chromium', 'firefox', 'webkit'],
    fixtureModes: ALL_MODES,
    caseCount: 0,
    links: { self: `/api/v1/suites/${overrides.id}` },
    ...overrides,
  };
}

export const CHECKOUT = suite({
  id: '00000000-0000-4000-8000-0000000000a1',
  suiteKey: 'checkout-smoke',
  displayName: 'Checkout smoke',
  revision: '1a2b3c4d5e6f',
  caseCount: 6,
});

export const CATALOG = suite({
  id: '00000000-0000-4000-8000-0000000000a2',
  suiteKey: 'catalog-regression',
  displayName: 'Catalog regression',
  revision: '6f5e4d3c2b1a',
  timeoutMs: 180_000,
  fixtureModes: ['PASS', 'PRODUCT_DEFECT'],
  caseCount: 12,
});

export const CHECKOUT_CASES: TestCase[] = [
  ['browse-catalog', 'Browse the catalog', false],
  ['add-to-cart', 'Add an item to the cart', false],
  ['cart-total', 'Cart total matches its lines', true],
  ['apply-coupon', 'Apply a coupon', false],
  ['checkout-happy-path', 'Check out', true],
  ['card-declined', 'A declined card shows an error', false],
].map(([caseKey, title, critical]) => ({
  caseKey: caseKey as string,
  title: title as string,
  tag: `@${caseKey as string}`,
  critical: critical as boolean,
  expectedDurationMs: null,
}));

export interface StubRequest {
  method: string;
  /** Path below /api/v1, e.g. `/projects`. */
  path: string;
  body: unknown;
}

export type StubHandler = (request: StubRequest) => Response | Promise<Response> | undefined;

export const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status < 400 ? 'application/json' : 'application/problem+json',
      ...headers,
    },
  });

export function problem(status: number, slug: string, title: string, extra: Partial<Problem> = {}) {
  return json(
    {
      type: `https://testgrid.local/problems/${slug}`,
      title,
      status,
      detail: `${title}.`,
      instance: '/api/v1/test',
      traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
      ...extra,
    } satisfies Problem,
    status,
  );
}

export interface ApiOptions {
  fixturesAllowed?: boolean;
  suites?: Suite[];
  details?: Record<string, SuiteDetail>;
}

/** GET /projects, /capabilities, /projects/{id}/suites and /suites/{id}; anything else 404s. */
export function catalogApi(options: ApiOptions = {}): StubHandler {
  const suites = options.suites ?? [CHECKOUT, CATALOG];
  const details = options.details ?? { [CHECKOUT.id]: { suite: CHECKOUT, cases: CHECKOUT_CASES } };
  return ({ method, path }) => {
    if (method !== 'GET') return undefined;
    if (path === '/projects') return json(PROJECTS);
    if (path === '/capabilities') return json({ fixturesAllowed: options.fixturesAllowed ?? true });
    if (path === `/projects/${PROJECT_ID}/suites`) return json({ items: suites, nextCursor: null });
    // The run list defaults to empty; runListApi() answers it with real pages.
    if (path === '/runs' || path.startsWith('/runs?')) return json({ items: [], nextCursor: null });
    const detail = /^\/suites\/(.+)$/.exec(path)?.[1];
    if (detail && details[detail]) return json(details[detail]);
    return undefined;
  };
}

/**
 * Installs a fetch stub. Handlers are tried in order; the first that answers wins, and a
 * request nobody answers gets a 404 Problem, so a test never silently hits the network.
 */
export function stubApi(...handlers: StubHandler[]) {
  const requests: StubRequest[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const method = init?.method ?? 'GET';
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : null;
    const request = { method, path: url.replace(/^\/api\/v1/, ''), body };
    requests.push(request);
    for (const handler of handlers) {
      const response = await handler(request);
      if (response) return response;
    }
    return problem(404, 'not-found', 'Not found', { detail: `${method} ${url} is not stubbed` });
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, requests, posts: () => requests.filter((r) => r.method === 'POST') };
}

let runCounter = 0;

/** A run summary with a unique id; queued `minutesAgo` before 2026-11-22T12:00:00Z. */
export function runSummary(
  overrides: Partial<RunSummary> & { minutesAgo?: number } = {},
): RunSummary {
  const { minutesAgo = 1, ...rest } = overrides;
  runCounter += 1;
  // Real UUIDv4s differ in their first 8 characters, which the UI shows as the short ID.
  const id = `${(0x1a2b3c00 + runCounter).toString(16)}-0000-4000-8000-000000000000`;
  const queuedAt = new Date(Date.UTC(2026, 10, 22, 12, 0, 0) - minutesAgo * 60_000).toISOString();
  return {
    id,
    projectId: PROJECT_ID,
    suiteKey: 'checkout-smoke',
    suiteRevision: CHECKOUT.revision,
    status: 'PASSED',
    trigger: 'MANUAL',
    fixture: null,
    source: null,
    targetCount: 3,
    queuedAt,
    startedAt: queuedAt,
    finishedAt: new Date(Date.parse(queuedAt) + 42_000).toISOString(),
    links: { self: `/api/v1/runs/${id}`, suite: null },
    ...rest,
  };
}

/**
 * GET /runs answered page by page: no cursor gets pages[0], cursor `pN` gets pages[N]. Each
 * page's nextCursor is filled in for you. `onRequest` sees the parsed query of every call.
 */
export function runListApi(
  pages: RunSummary[][],
  onRequest: (query: URLSearchParams) => Response | undefined = () => undefined,
): StubHandler {
  return ({ method, path }) => {
    if (method !== 'GET' || !(path === '/runs' || path.startsWith('/runs?'))) return undefined;
    const query = new URLSearchParams(path.split('?')[1] ?? '');
    const answer = onRequest(query);
    if (answer) return answer;
    const index = Number(query.get('cursor')?.slice(1) ?? 0);
    const page: PageRunSummary = {
      items: pages[index] ?? [],
      nextCursor: index + 1 < pages.length ? `p${index + 1}` : null,
    };
    return json(page);
  };
}

const RUN_STATUS_ORDER = [
  'QUEUED',
  'RUNNING',
  'PASSED',
  'FAILED',
  'TIMED_OUT',
  'INFRA_ERROR',
  'CANCELLED',
] as const;

/** GET /projects/{id}/run-counts with the given counts (others 0), every status listed. */
export function runCountsApi(
  counts: Partial<Record<(typeof RUN_STATUS_ORDER)[number], number>>,
  hours = 24,
): StubHandler {
  return ({ method, path }) => {
    if (method !== 'GET' || !path.startsWith(`/projects/${PROJECT_ID}/run-counts`))
      return undefined;
    const items = RUN_STATUS_ORDER.map((status) => ({ status, count: counts[status] ?? 0 }));
    return json({
      from: '2026-11-21T12:00:00Z',
      to: '2026-11-22T12:00:00Z',
      hours,
      total: items.reduce((n, i) => n + i.count, 0),
      items,
    });
  };
}

export function worker(overrides: Partial<WorkerView> & Pick<WorkerView, 'name'>): WorkerView {
  return {
    id: `w-${overrides.name}`,
    version: '0.5.0',
    executor: 'LOCAL',
    engines: ['chromium', 'firefox', 'webkit'],
    slots: 1,
    state: 'ACTIVE',
    startedAt: '2026-11-22T07:41:02Z',
    lastSeenAt: '2026-11-22T11:59:55Z',
    activeAttempts: 0,
    ...overrides,
  };
}

/** GET /workers. */
export function workersApi(response: Partial<WorkersResponse> = {}): StubHandler {
  return ({ method, path }) =>
    method === 'GET' && path === '/workers'
      ? json({
          items: [],
          globalMaxSessions: 3,
          activeSessions: 0,
          dispatchedUnclaimed: 0,
          ...response,
        } satisfies WorkersResponse)
      : undefined;
}

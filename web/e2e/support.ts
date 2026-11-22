import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';
import type { components } from '@testgrid/contracts';

// Helpers for the dashboard E2E (live, not mocked). The API is reached through the web server's
// /api proxy, exactly as the browser reaches it.

type RunResponse = components['schemas']['RunResponse'];
type CreateRunRequest = components['schemas']['CreateRunRequest'];
type Engine = CreateRunRequest['targets'][number]['engine'];
type FixtureMode = NonNullable<CreateRunRequest['fixture']>['mode'];

const TERMINAL = ['PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED'];

export async function projectId(request: APIRequestContext): Promise<string> {
  const response = await request.get('/api/v1/projects');
  expect(response.ok(), 'the stack is up (run `make up` first)').toBe(true);
  const body = (await response.json()) as { items: { id: string }[] };
  const id = body.items[0]?.id;
  if (!id) throw new Error('the API returned no project');
  return id;
}

/** Starts a run through the API (setup for tests whose subject is not the new-run dialog). */
export async function createRun(
  request: APIRequestContext,
  suiteKey: string,
  engines: Engine[],
  mode: FixtureMode,
): Promise<RunResponse> {
  const project = await projectId(request);
  const response = await request.post(`/api/v1/projects/${project}/runs`, {
    data: {
      suiteKey,
      targets: engines.map((engine) => ({ executor: 'LOCAL', engine })),
      trigger: 'MANUAL',
      fixture: { mode, seed: 1 },
      idempotencyKey: `e2e-${crypto.randomUUID()}`,
    } satisfies CreateRunRequest,
  });
  expect(response.status()).toBe(202);
  return (await response.json()) as RunResponse;
}

/**
 * The engines some ACTIVE worker serves, in contract order. A target for an engine no worker
 * serves is dispatched but never claimed, and it keeps holding a slot under the global cap, so
 * the tests only ask for engines that will actually run.
 */
export async function servedEngines(request: APIRequestContext): Promise<Engine[]> {
  const response = await request.get('/api/v1/workers');
  expect(response.ok()).toBe(true);
  const body = (await response.json()) as components['schemas']['WorkersResponse'];
  const served = new Set(body.items.filter((w) => w.state === 'ACTIVE').flatMap((w) => w.engines));
  const engines = (['chromium', 'firefox', 'webkit'] as const).filter((e) => served.has(e));
  expect(engines.length, 'an ACTIVE worker is registered').toBeGreaterThan(0);
  return engines;
}

export async function getRun(request: APIRequestContext, runId: string): Promise<RunResponse> {
  const response = await request.get(`/api/v1/runs/${runId}`);
  expect(response.ok()).toBe(true);
  return (await response.json()) as RunResponse;
}

export const isTerminal = (status: string) => TERMINAL.includes(status);

/** Polls the API (not the page) until `check` holds, so the page can be compared against it. */
export async function waitForRun(
  request: APIRequestContext,
  runId: string,
  check: (run: RunResponse) => boolean,
  timeoutMs = 180_000,
): Promise<RunResponse> {
  let latest = await getRun(request, runId);
  await expect
    .poll(
      async () => {
        latest = await getRun(request, runId);
        return check(latest);
      },
      { timeout: timeoutMs, intervals: [1_000] },
    )
    .toBe(true);
  return latest;
}

export async function cancelRun(request: APIRequestContext, runId: string) {
  await request.post(`/api/v1/runs/${runId}/cancel`);
}

/** Cancels whatever the test left unfinished, so the single worker slot is free for the next. */
export async function cleanUp(request: APIRequestContext, runIds: string[]) {
  for (const id of runIds) {
    const run = await getRun(request, id);
    if (!isTerminal(run.status)) {
      await cancelRun(request, id);
      await waitForRun(request, id, (r) => isTerminal(r.status), 120_000);
    }
  }
}

export function runIdFromUrl(page: Page): string {
  const match = /\/runs\/([0-9a-f-]{36})$/.exec(new URL(page.url()).pathname);
  if (!match?.[1]) throw new Error(`not on a run page: ${page.url()}`);
  return match[1];
}

/** The run header's status badge (the first badge in the section titled by #run-title). */
export const headerStatus = (page: Page) =>
  page.locator('section[aria-labelledby="run-title"]').getByRole('img').first();

/** Presses Tab until `target` has focus: the keyboard-only path a person would take. */
export async function tabTo(page: Page, target: Locator, maxPresses = 40) {
  for (let i = 0; i < maxPresses; i++) {
    if (await target.evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  throw new Error(`could not reach ${target.toString()} with Tab in ${maxPresses} presses`);
}

const REPO = path.resolve(import.meta.dirname, '../..');

/**
 * Restarts the web container (nginx), which cuts every open connection through it, including a
 * page's event stream. Playwright can block new requests but cannot end a stream that is
 * already flowing; this is the mid-run kill for AC-3.
 */
export function restartProxy() {
  execFileSync(
    'docker',
    ['compose', '-f', 'infra/compose/compose.yaml', '--env-file', '.env', 'restart', 'web'],
    { cwd: REPO, stdio: 'ignore', timeout: 60_000 },
  );
}

import { act, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RerunRequest, RunResponse, RunResults } from '../../api/types.ts';
import { catalogApi, json, problem, stubApi, type StubHandler } from '../../test/api.ts';
import { expectNoSeriousA11yViolations } from '../../test/axe.ts';
import { FakeEventSource } from '../../test/eventSource.ts';
import { renderApp } from '../../test/render.tsx';
import {
  attempt,
  attemptResults,
  result,
  results,
  run,
  target,
  timelineFor,
} from '../../test/runFixtures.ts';

// Component tests against a fake API (mocked, not live).

afterEach(() => {
  vi.unstubAllGlobals();
});

/** GET /runs/{id} and /runs/{id}/results for the given runs; POSTs go to `post`. */
function runApi(
  runs: { run: RunResponse; results: RunResults }[],
  post: StubHandler = () => undefined,
): StubHandler {
  return (request) => {
    if (request.method === 'POST') return post(request);
    for (const { run: r, results: res } of runs) {
      if (request.path === `/runs/${r.id}`) return json(r);
      if (request.path === `/runs/${r.id}/results`) return json(res);
      if (request.path === `/runs/${r.id}/timeline`) return json(timelineFor(r, res));
    }
    return undefined;
  };
}

const failedRun = run({
  targets: [
    target('chromium', 'FAILED'),
    target('firefox', 'PASSED'),
    target('webkit', 'TIMED_OUT'),
  ],
});
const failedResults = results(failedRun, [
  [
    attemptResults(attempt(2, 'FAILED', { errorClass: 'ASSERTION' }), [
      result('cart-total', 'FAILED', 900),
      result('add-to-cart', 'PASSED', 400),
    ]),
    attemptResults(
      attempt(1, 'ABANDONED', {
        errorClass: 'LEASE_EXPIRED',
        leaseExpired: true,
        finishedAt: null,
      }),
      [],
      'worker stopped heartbeating',
    ),
  ],
  [
    attemptResults(attempt(1, 'PASSED'), [
      result('cart-total', 'PASSED', 800),
      result('add-to-cart', 'PASSED', 300),
    ]),
  ],
  [
    attemptResults(attempt(1, 'TIMED_OUT', { errorClass: 'TEST_TIMEOUT' }), [
      result('cart-total', 'TIMED_OUT', 120_000, { errorClass: 'TEST_TIMEOUT' }),
      result('add-to-cart', 'PASSED', 500),
    ]),
  ],
]);

async function openFailedRun(post?: StubHandler) {
  const api = stubApi(runApi([{ run: failedRun, results: failedResults }], post), catalogApi());
  const app = renderApp(`/runs/${failedRun.id}`);
  await screen.findByRole('heading', { level: 1, name: 'Checkout smoke' });
  return { ...app, api };
}

describe('run detail page (PRD-05 FR-2, T4)', () => {
  it('heads the page with the aggregate status, what ran, and a factual summary', async () => {
    await openFailedRun();
    const header = screen.getByRole('region', { name: 'Checkout smoke' });
    expect(within(header).getByRole('img', { name: 'Run status: FAILED' })).toBeVisible();
    expect(within(header).getByText('checkout-smoke @ 1a2b3c4d5e6f')).toBeVisible();
    expect(
      within(header).getByText(
        '3 targets: 1 FAILED, 1 TIMED_OUT, 1 PASSED. 4 of 6 case results PASSED.',
      ),
    ).toBeVisible();
    const meta = (term: string) => within(header).getByText(term).nextElementSibling;
    expect(meta('Trigger')).toHaveTextContent('MANUAL');
    expect(meta('Fixture')).toHaveTextContent('PRODUCT_DEFECT · seed 42');
    expect(meta('Source')).toHaveTextContent('main · 0123456789ab');
    expect(meta('Queued')?.querySelector('time')).toHaveAttribute('datetime', failedRun.queuedAt);
    expect(meta('Queued')).toHaveTextContent('11:50:00.000');
    expect(meta('Duration')).toHaveTextContent('41.8 s');
    expect(document.title).toBe('Run 7c1e04b9 · FAILED · TestGrid');
  });

  it('fills the matrix from each target’s latest attempt', async () => {
    await openFailedRun();
    await screen.findByRole('rowheader', { name: /cart-total/ });
    const matrix = screen.getByRole('table', { name: /Case results by engine/ });
    const headers = within(matrix)
      .getAllByRole('columnheader')
      .map((h) => h.textContent);
    expect(headers[1]).toContain('chromium');
    expect(headers[1]).toContain('attempt 2 of 3');

    const row = within(matrix)
      .getByRole('rowheader', { name: /cart-total/ })
      .closest('tr');
    const cells = within(row as HTMLElement).getAllByRole('cell');
    expect(within(cells[0] as HTMLElement).getByRole('img')).toHaveAccessibleName(
      'Case status: FAILED',
    );
    expect(cells[0]).toHaveTextContent('ASSERTION · 0.9 s');
    expect(within(cells[1] as HTMLElement).getByRole('img')).toHaveAccessibleName(
      'Case status: PASSED',
    );
    expect(within(cells[2] as HTMLElement).getByRole('img')).toHaveAccessibleName(
      'Case status: TIMED_OUT',
    );

    // A suite case no attempt reported: each target's own state, never an invented outcome.
    const noResult = within(matrix)
      .getByRole('rowheader', { name: /apply-coupon/ })
      .closest('tr');
    expect(
      within(noResult as HTMLElement)
        .getAllByRole('img')
        .map((i) => i.getAttribute('aria-label')),
    ).toEqual(['Target status: FAILED', 'Target status: PASSED', 'Target status: TIMED_OUT']);
  });

  it('lists every attempt per target, newest first, with times, worker and lease state', async () => {
    await openFailedRun();
    const chromium = screen.getByRole('article', { name: 'local:chromium:0' });
    expect(within(chromium).getByText('2 attempts of 3')).toBeVisible();
    const items = within(chromium).getAllByRole('listitem');
    expect(within(items[0] as HTMLElement).getByRole('img')).toHaveAccessibleName(
      'Attempt status: FAILED',
    );
    expect(within(items[1] as HTMLElement).getByRole('img')).toHaveAccessibleName(
      'Attempt status: ABANDONED',
    );
    expect(within(items[1] as HTMLElement).getByText(/lease expired/)).toBeVisible();
    expect(within(items[1] as HTMLElement).getByText('worker stopped heartbeating')).toBeVisible();
    expect(within(items[0] as HTMLElement).getByText('worker-bee2be453300')).toBeVisible();
  });

  it('shows case results on request, per engine, sortable by status and duration', async () => {
    const { user } = await openFailedRun();
    const toggle = screen.getByRole('button', { name: 'Show case results' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('tablist')).toBeNull();
    await user.click(toggle);

    const tabs = within(screen.getByRole('tablist', { name: 'Engine' })).getAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual(['chromium', 'firefox', 'webkit']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    let table = screen.getByRole('table', { name: 'Case results on chromium, attempt 2' });
    const keys = () =>
      within(table)
        .getAllByRole('row')
        .slice(1)
        .map((r) => within(r).getAllByRole('cell')[0]?.textContent);
    expect(keys()).toEqual(['cart-total', 'add-to-cart']);

    const duration = within(table).getByRole('columnheader', { name: /Duration/ });
    await user.click(within(duration).getByRole('button'));
    expect(duration).toHaveAttribute('aria-sort', 'descending');
    expect(within(table).getByRole('columnheader', { name: /Status/ })).toHaveAttribute(
      'aria-sort',
      'none',
    );
    await user.click(within(duration).getByRole('button'));
    expect(duration).toHaveAttribute('aria-sort', 'ascending');
    expect(keys()).toEqual(['add-to-cart', 'cart-total']);

    // Arrow keys move between engine tabs.
    tabs[0]?.focus();
    await user.keyboard('{ArrowRight}');
    expect(tabs[1]).toHaveFocus();
    expect(tabs[1]).toHaveAttribute('aria-selected', 'true');
    table = screen.getByRole('table', { name: 'Case results on firefox, attempt 1' });
    await user.keyboard('{End}');
    expect(tabs[2]).toHaveFocus();
    await user.keyboard('{ArrowRight}');
    expect(tabs[0]).toHaveFocus();
  });

  it('reruns only the failed targets with an idempotency key, then opens the new run', async () => {
    const rerun = run({
      id: 'b4aab9b3-0000-4000-8000-000000000002',
      trigger: 'RERUN',
      status: 'QUEUED',
      parentRunId: failedRun.id,
      targets: [target('chromium', 'QUEUED'), target('webkit', 'QUEUED')],
    });
    const posts: { path: string; body: RerunRequest }[] = [];
    const { user, router, api } = await openFailedRun(({ path, body }) => {
      posts.push({ path, body: body as RerunRequest });
      return json(rerun, 202);
    });
    api.fetchMock.mockClear();
    stubApi(
      runApi(
        [
          { run: failedRun, results: failedResults },
          { run: rerun, results: results(rerun, []) },
        ],
        ({ path, body }) => {
          posts.push({ path, body: body as RerunRequest });
          return json(rerun, 202);
        },
      ),
      catalogApi(),
    );

    const button = screen.getByRole('button', { name: 'Rerun failed targets (chromium, webkit)' });
    await user.click(button);

    await waitFor(() => expect(router.state.location.pathname).toBe(`/runs/${rerun.id}`));
    expect(posts).toHaveLength(1);
    expect(posts[0]?.path).toBe(`/runs/${failedRun.id}/rerun`);
    expect(posts[0]?.body).toEqual({
      scope: 'FAILED_TARGETS',
      idempotencyKey: expect.stringMatching(/^[A-Za-z0-9._:-]{1,200}$/) as string,
    });
    // The new run links back to the one it reruns.
    const back = await screen.findByRole('link', { name: '7c1e04b9' });
    expect(back).toHaveAttribute('href', `/runs/${failedRun.id}`);
    expect(screen.getByText(/Rerun of/)).toBeVisible();
  });

  it('retries a rerun with the same key after a lost answer', async () => {
    let calls = 0;
    const bodies: RerunRequest[] = [];
    const { user } = await openFailedRun(({ body }) => {
      bodies.push(body as RerunRequest);
      calls += 1;
      return calls === 1
        ? Promise.reject(new TypeError('Failed to fetch'))
        : problem(409, 'idempotency-conflict', 'Idempotency conflict');
    });
    await user.click(screen.getByRole('button', { name: 'Rerun all' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The API could not be reached');
    await user.click(screen.getByRole('button', { name: 'Rerun all' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1]?.idempotencyKey).toBe(bodies[0]?.idempotencyKey);
    expect(bodies.map((b) => b.scope)).toEqual(['ALL', 'ALL']);
  });

  it('offers no "Rerun failed targets" when nothing failed', async () => {
    const passed = run({ status: 'PASSED', targets: [target('chromium', 'PASSED')] });
    stubApi(runApi([{ run: passed, results: results(passed, []) }]), catalogApi());
    renderApp(`/runs/${passed.id}`);
    expect(await screen.findByRole('button', { name: 'Rerun all' })).toBeVisible();
    expect(screen.queryByRole('button', { name: /Rerun failed/ })).toBeNull();
  });

  it('links to every rerun of this run', async () => {
    const withReruns = run({
      ...failedRun,
      rerunIds: ['b4aab9b3-0000-4000-8000-000000000002', 'c94b8ee3-0000-4000-8000-000000000003'],
    });
    stubApi(runApi([{ run: withReruns, results: failedResults }]), catalogApi());
    renderApp(`/runs/${withReruns.id}`);
    expect(await screen.findByRole('link', { name: 'b4aab9b3' })).toHaveAttribute(
      'href',
      '/runs/b4aab9b3-0000-4000-8000-000000000002',
    );
    expect(screen.getByRole('link', { name: 'c94b8ee3' })).toBeVisible();
    expect(screen.getByText(/Rerun as, oldest first/)).toBeVisible();
  });

  describe('an unfinished run', () => {
    const running = run({
      id: '0e6651a1-0000-4000-8000-000000000004',
      status: 'RUNNING',
      finishedAt: null,
      targets: [target('chromium', 'RUNNING'), target('webkit', 'QUEUED')],
    });
    const runningResults = results(running, [[attemptResults(attempt(1, 'RUNNING'))], []]);

    it('can be cancelled after a confirmation, and then says the cancel is pending', async () => {
      const cancelled = { ...running, cancelRequestedAt: '2026-11-22T11:52:00.000Z' };
      const posts: string[] = [];
      stubApi(
        runApi([{ run: running, results: runningResults }], ({ path }) => {
          posts.push(path);
          return json(cancelled, 202);
        }),
        catalogApi(),
      );
      const { user } = renderApp(`/runs/${running.id}`);
      expect(screen.queryByRole('button', { name: /Rerun/ })).toBeNull();
      await user.click(await screen.findByRole('button', { name: 'Cancel run' }));

      const confirm = screen.getByRole('group', { name: /Cancel this run\?/ });
      const yes = within(confirm).getByRole('button', { name: 'Yes, cancel run' });
      expect(yes).toHaveFocus();
      await user.click(within(confirm).getByRole('button', { name: 'Keep running' }));
      expect(posts).toEqual([]);

      await user.click(screen.getByRole('button', { name: 'Cancel run' }));
      await user.keyboard('{Enter}');
      expect(await screen.findByText(/Cancel requested at/)).toHaveTextContent(
        'Cancel requested at 11:52:00.000. Running targets stop at their next heartbeat.',
      );
      expect(posts).toEqual([`/runs/${running.id}/cancel`]);
    });

    it('shows RUNNING and QUEUED in the matrix, pulsing once the live stream is open', async () => {
      stubApi(runApi([{ run: running, results: runningResults }]), catalogApi());
      renderApp(`/runs/${running.id}`);
      await screen.findByText('Connecting to live updates…');
      act(() => FakeEventSource.latest().open());
      expect(await screen.findByText('Live')).toBeVisible();
      // The matrix waits for the suite's case list instead of flashing an empty table.
      const row = (await screen.findByRole('rowheader', { name: /cart-total/ })).closest('tr');
      const badges = within(row as HTMLElement).getAllByRole('img');
      expect(badges.map((b) => b.getAttribute('aria-label'))).toEqual([
        'Target status: RUNNING',
        'Target status: QUEUED',
      ]);
      expect(badges[0]?.querySelector('svg')).toHaveClass('tg-pulse');
    });

    it('stops looking live and says so when a refresh fails', async () => {
      let fail = false;
      stubApi(
        (req) =>
          fail && req.path === `/runs/${running.id}`
            ? problem(503, 'internal-error', 'Service unavailable')
            : undefined,
        runApi([{ run: running, results: runningResults }]),
        catalogApi(),
      );
      const { client } = renderApp(`/runs/${running.id}`);
      await screen.findByText('Connecting to live updates…');
      act(() => FakeEventSource.latest().open());
      await screen.findByText('Live');
      fail = true;
      await client.refetchQueries({ queryKey: ['runs', 'detail', running.id], exact: true });
      const alert = await screen.findByRole('alert', {}, { timeout: 8000 });
      expect(alert).toHaveTextContent('Could not refresh this run.');
      const header = screen.getByRole('region', { name: 'Checkout smoke' });
      expect(
        within(header).getByRole('img', { name: 'Run status: RUNNING' }).querySelector('svg'),
      ).not.toHaveClass('tg-pulse');
    }, 10_000);
  });

  it('says the cases are loading instead of showing an empty matrix', async () => {
    stubApi(
      ({ path }) =>
        path.startsWith('/suites/') ? new Promise<Response>(() => undefined) : undefined,
      runApi([{ run: failedRun, results: failedResults }]),
      catalogApi(),
    );
    renderApp(`/runs/${failedRun.id}`);
    expect(await screen.findByText('Loading cases…')).toBeVisible();
    expect(screen.queryByRole('table', { name: /Case results by engine/ })).toBeNull();
  });

  it('shows the API’s Problem Details for an unknown run, with a way back', async () => {
    stubApi(catalogApi());
    renderApp('/runs/00000000-0000-4000-8000-0000000000ff');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Not found');
    expect(within(alert).getByRole('link', { name: 'Back to runs' })).toHaveAttribute(
      'href',
      '/runs',
    );
  });

  it('has no serious or critical axe violations, with the case table open', async () => {
    const { user } = await openFailedRun();
    await user.click(screen.getByRole('button', { name: 'Show case results' }));
    await screen.findByRole('tablist');
    await expectNoSeriousA11yViolations();
  });
});

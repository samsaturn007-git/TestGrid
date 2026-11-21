import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { catalogApi, problem, runListApi, runSummary, stubApi } from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';

// Component tests against a fake API (mocked, not live).

beforeEach(() => {
  // Only Date is faked, so relative times are fixed and userEvent's timers still run.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-22T12:00:00Z'));
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

/** The query of every GET /runs the page made, in order. */
function runQueries(requests: { method: string; path: string }[]) {
  return requests
    .filter((r) => r.method === 'GET' && r.path.startsWith('/runs?'))
    .map((r) => new URLSearchParams(r.path.split('?')[1]));
}

const table = () => screen.findByRole('table', { name: 'Runs, newest first' });
const bodyRows = (t: HTMLElement) => within(t).getAllByRole('row').slice(1);

describe('runs page (PRD-05 FR-2, T3)', () => {
  it('shows each run from the API with its status, link, source and times', async () => {
    const failed = runSummary({
      status: 'FAILED',
      fixture: { mode: 'PRODUCT_DEFECT', seed: 42 },
      source: { branch: 'main', commitSha: '0123456789abcdef' },
      minutesAgo: 13,
    });
    const running = runSummary({ status: 'RUNNING', finishedAt: null, minutesAgo: 0.15 });
    const queued = runSummary({
      status: 'QUEUED',
      startedAt: null,
      finishedAt: null,
      trigger: 'CI',
    });
    stubApi(runListApi([[running, failed, queued]]), catalogApi());
    renderApp('/runs');

    const rows = bodyRows(await table());
    expect(rows).toHaveLength(3);

    const [r0, r1, r2] = rows.map((r) => within(r));
    expect(r1?.getByRole('img', { name: 'Run status: FAILED' })).toBeVisible();
    expect(r1?.getByRole('link', { name: failed.id.slice(0, 8) })).toHaveAttribute(
      'href',
      `/runs/${failed.id}`,
    );
    expect(r1?.getByText('PRODUCT_DEFECT · 42')).toBeVisible();
    expect(r1?.getByText('main')).toBeVisible();
    expect(r1?.getByText('0123456')).toHaveAttribute('title', '0123456789abcdef');
    const time = rows[1]?.querySelector('time');
    expect(time).toHaveTextContent('13 min ago');
    expect(time).toHaveAttribute('datetime', failed.queuedAt);
    expect(time).toHaveAttribute('title', 'Nov 22, 2026, 11:47:00 UTC');
    expect(r1?.getByText('42.0 s')).toBeVisible();

    expect(r0?.getByText('9.0 s so far')).toBeVisible();
    expect(r2?.getByText('not started')).toHaveClass('sr-only');
    expect(r2?.getByText('no source')).toHaveClass('sr-only');
    expect(screen.getByText('All 3 runs loaded.')).toBeVisible();
    expect(screen.getByText('3 loaded · newest first')).toBeVisible();
  });

  it('opens with the filters in the address and sends them to the API', async () => {
    const api = stubApi(runListApi([[runSummary({ status: 'FAILED' })]]), catalogApi());
    renderApp('/runs?status=FAILED&suite=checkout-smoke&branch=main&trigger=CI');
    await table();

    const query = runQueries(api.requests)[0];
    expect(query?.getAll('status')).toEqual(['FAILED']);
    expect(query?.get('suiteKey')).toBe('checkout-smoke');
    expect(query?.get('branch')).toBe('main');
    expect(query?.getAll('trigger')).toEqual(['CI']);

    const search = screen.getByRole('search', { name: 'Filter runs' });
    expect(within(search).getByLabelText('Status')).toHaveValue('FAILED');
    expect(within(search).getByLabelText('Suite')).toHaveValue('checkout-smoke');
    expect(within(search).getByLabelText('Trigger')).toHaveValue('CI');
    expect(within(search).getByLabelText('Branch')).toHaveValue('main');
    expect(
      within(screen.getByRole('list', { name: 'Active filters' }))
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['statusFAILED', 'suitecheckout-smoke', 'branchmain', 'triggerCI']);
  });

  it('puts a changed filter in the URL, refetches, and Back undoes it', async () => {
    const api = stubApi(runListApi([[runSummary()]]), catalogApi());
    const { user, router } = renderApp('/runs');
    await table();

    await user.selectOptions(screen.getByLabelText('Status'), 'TIMED_OUT');
    await waitFor(() => expect(router.state.location.search).toBe('?status=TIMED_OUT'));
    await waitFor(() =>
      expect(runQueries(api.requests).at(-1)?.getAll('status')).toEqual(['TIMED_OUT']),
    );

    await user.selectOptions(screen.getByLabelText('Suite'), 'catalog-regression');
    await waitFor(() =>
      expect(router.state.location.search).toBe('?status=TIMED_OUT&suite=catalog-regression'),
    );

    await router.navigate(-1);
    await waitFor(() => expect(router.state.location.search).toBe('?status=TIMED_OUT'));
    expect(screen.getByLabelText('Suite')).toHaveValue('');
  });

  it('removes one chip, or clears every filter', async () => {
    stubApi(runListApi([[runSummary()]]), catalogApi());
    const { user, router } = renderApp('/runs?status=FAILED&status=TIMED_OUT&trigger=CI');
    await table();

    await user.click(screen.getByRole('button', { name: 'Remove filter status: FAILED' }));
    await waitFor(() => expect(router.state.location.search).toBe('?status=TIMED_OUT&trigger=CI'));

    await user.click(screen.getByRole('button', { name: 'Clear all' }));
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(screen.queryByRole('list', { name: 'Active filters' })).toBeNull();
  });

  it('shows several statuses from a link as one choice and sends all of them', async () => {
    const api = stubApi(runListApi([[runSummary()]]), catalogApi());
    renderApp('/runs?status=FAILED,TIMED_OUT');
    await table();
    const select = screen.getByLabelText('Status');
    expect(within(select).getByRole('option', { selected: true })).toHaveTextContent(
      'FAILED, TIMED_OUT',
    );
    expect(runQueries(api.requests)[0]?.getAll('status')).toEqual(['FAILED', 'TIMED_OUT']);
  });

  it('applies a branch on Enter, and refuses one the API would reject', async () => {
    const api = stubApi(runListApi([[runSummary()]]), catalogApi());
    const { user, router } = renderApp('/runs');
    await table();
    const branch = screen.getByLabelText('Branch');

    await user.type(branch, 'a..b{Enter}');
    expect(branch).toHaveAttribute('aria-invalid', 'true');
    expect(branch).toHaveAccessibleDescription(/no "\.\."/);
    expect(router.state.location.search).toBe('');

    await user.clear(branch);
    await user.type(branch, 'feat/coupon{Enter}');
    await waitFor(() => expect(router.state.location.search).toBe('?branch=feat%2Fcoupon'));
    expect(branch).toHaveAttribute('aria-invalid', 'false');
    await waitFor(() => expect(runQueries(api.requests).at(-1)?.get('branch')).toBe('feat/coupon'));
  });

  it('loads older runs with the cursor and the same filters, then moves focus to them', async () => {
    const first = Array.from({ length: 3 }, (_, i) => runSummary({ minutesAgo: i + 1 }));
    const second = Array.from({ length: 2 }, (_, i) => runSummary({ minutesAgo: i + 10 }));
    const api = stubApi(runListApi([first, second]), catalogApi());
    const { user } = renderApp('/runs?trigger=MANUAL');

    expect(bodyRows(await table())).toHaveLength(3);
    expect(screen.getByText('3 runs loaded. There are older runs.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Load more' }));

    await waitFor(async () => expect(bodyRows(await table())).toHaveLength(5));
    const queries = runQueries(api.requests);
    expect(queries[1]?.get('cursor')).toBe('p1');
    expect(queries[1]?.getAll('trigger')).toEqual(['MANUAL']);
    expect(screen.getByText('All 5 runs loaded.')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
    await waitFor(() =>
      expect(screen.getByRole('link', { name: second[0]?.id.slice(0, 8) ?? '' })).toHaveFocus(),
    );
  });

  it('keeps the loaded runs when an older page fails, and says so', async () => {
    stubApi(
      runListApi([[runSummary()], [runSummary()]], (q) =>
        q.get('cursor') ? problem(400, 'validation-failed', 'Validation failed') : undefined,
      ),
      catalogApi(),
    );
    const { user } = renderApp('/runs');
    await table();
    await user.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Validation failed');
    expect(bodyRows(await table())).toHaveLength(1);
  });

  it('says so when a refresh fails, instead of showing old runs as current', async () => {
    let fail = false;
    stubApi(
      runListApi([[runSummary({ status: 'RUNNING', finishedAt: null })]], () =>
        fail ? problem(503, 'internal-error', 'Service unavailable') : undefined,
      ),
      catalogApi(),
    );
    const { client } = renderApp('/runs');
    await table();
    fail = true;
    await client.refetchQueries({ queryKey: ['runs', 'list'] });
    const alert = await screen.findByRole('alert', {}, { timeout: 8000 });
    expect(alert).toHaveTextContent('Could not refresh the list. These runs were loaded at');
    expect(alert).toHaveTextContent('Nov 22, 2026, 12:00:00 UTC');
    expect(bodyRows(await table())).toHaveLength(1);
  }, 10_000);

  it('with no runs at all, says how to create one', async () => {
    stubApi(runListApi([[]]), catalogApi());
    const { user } = renderApp('/runs');
    expect(await screen.findByRole('heading', { name: 'No runs yet' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Start a run' }));
    expect(await screen.findByRole('dialog', { name: 'New run' })).toBeInTheDocument();
  });

  it('with filters that match nothing, offers to clear them', async () => {
    stubApi(runListApi([[]]), catalogApi());
    const { user, router } = renderApp('/runs?status=CANCELLED');
    expect(
      await screen.findByRole('heading', { name: 'No runs match these filters' }),
    ).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Clear all filters' }));
    await waitFor(() => expect(router.state.location.search).toBe(''));
  });

  it('shows the API’s answer to a bad value in a shared link, with a way out', async () => {
    stubApi(
      runListApi([[runSummary()]], (q) =>
        q.getAll('status').includes('GREEN')
          ? problem(400, 'validation-failed', 'Validation failed', {
              detail: 'invalid value for status',
            })
          : undefined,
      ),
      catalogApi(),
    );
    const { user, router } = renderApp('/runs?status=GREEN');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('invalid value for status');
    await user.click(within(alert).getByRole('button', { name: 'Clear all filters' }));
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(await table()).toBeInTheDocument();
  });

  it('has no serious or critical axe violations with runs and filters showing', async () => {
    stubApi(
      runListApi([
        [runSummary({ status: 'FAILED' }), runSummary({ status: 'INFRA_ERROR' })],
        [runSummary()],
      ]),
      catalogApi(),
    );
    renderApp('/runs?status=FAILED&suite=checkout-smoke');
    await table();
    await expectNoSeriousA11yViolations();
  });
});

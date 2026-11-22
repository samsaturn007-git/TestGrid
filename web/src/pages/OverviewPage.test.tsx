import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  catalogApi,
  problem,
  PROJECT_ID,
  runCountsApi,
  runListApi,
  runSummary,
  stubApi,
  worker,
  workersApi,
} from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';

// Component tests against a fake API (mocked, not live).

const workers = workersApi({
  items: [worker({ name: 'worker-bee2be453300', activeAttempts: 2, slots: 2 })],
  globalMaxSessions: 3,
  activeSessions: 2,
  dispatchedUnclaimed: 0,
});

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-22T12:00:00Z'));
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('overview page (PRD-05 FR-2, T6)', () => {
  it('shows every run status count for the last 24 h, from the API', async () => {
    const api = stubApi(
      runCountsApi({ RUNNING: 1, PASSED: 4, FAILED: 2 }),
      runListApi([[]]),
      workers,
      catalogApi(),
    );
    renderApp('/');
    const counts = await screen.findByRole('region', { name: 'Run status · last 24 h' });
    const tiles = await within(counts).findAllByRole('listitem');
    expect(tiles.map((t) => t.getAttribute('aria-label'))).toEqual([
      '0 QUEUED runs in the last 24 hours',
      '1 RUNNING run in the last 24 hours',
      '4 PASSED runs in the last 24 hours',
      '2 FAILED runs in the last 24 hours',
      '0 TIMED_OUT runs in the last 24 hours',
      '0 INFRA_ERROR runs in the last 24 hours',
      '0 CANCELLED runs in the last 24 hours',
    ]);
    expect(within(tiles[0] as HTMLElement).getByText('0')).toHaveClass('text-text-muted');
    expect(screen.getByText(/Last 24 h · since/)).toHaveTextContent('7 runs');
    expect(api.requests.some((r) => r.path === `/projects/${PROJECT_ID}/run-counts?hours=24`)).toBe(
      true,
    );
  });

  it('lists recent runs with a link, trigger, source and age', async () => {
    const failed = runSummary({
      status: 'FAILED',
      trigger: 'CI',
      source: { branch: 'main', commitSha: '0123456789abcdef' },
      minutesAgo: 13,
    });
    const api = stubApi(runCountsApi({}), runListApi([[failed]]), workers, catalogApi());
    renderApp('/');
    const recent = await screen.findByRole('list', { name: 'Recent runs' });
    const item = within(recent).getAllByRole('listitem')[0] as HTMLElement;
    expect(within(item).getByRole('img', { name: 'Run status: FAILED' })).toBeVisible();
    expect(within(item).getByRole('link', { name: /checkout-smoke/ })).toHaveAttribute(
      'href',
      `/runs/${failed.id}`,
    );
    expect(item).toHaveTextContent('CI · main · 0123456');
    expect(within(item).getByText('13 min ago')).toBeVisible();
    expect(api.requests.some((r) => r.path === '/runs?limit=8')).toBe(true);
    expect(screen.getByRole('link', { name: /All runs/ })).toHaveAttribute('href', '/runs');
  });

  it('offers to start a run when there are none', async () => {
    vi.useRealTimers();
    stubApi(runCountsApi({}), runListApi([[]]), workers, catalogApi());
    const { user } = renderApp('/');
    await user.click(await screen.findByRole('button', { name: 'Start a run' }));
    expect(await screen.findByRole('dialog', { name: 'New run' })).toBeInTheDocument();
  });

  it('shows workers online and session use, from the registry', async () => {
    stubApi(runCountsApi({}), runListApi([[]]), workers, catalogApi());
    renderApp('/');
    const panel = await screen.findByRole('region', { name: 'Workers' });
    expect(await within(panel).findByRole('img', { name: '2 of 3 sessions in use' })).toBeVisible();
    expect(within(panel).getByText('0 dispatched, not yet claimed')).toBeVisible();
    expect(
      within(panel).getByRole('img', { name: 'Worker state: ACTIVE' }).closest('div'),
    ).toHaveTextContent('1');
    expect(within(panel).getByRole('link', { name: /Open workers/ })).toHaveAttribute(
      'href',
      '/workers',
    );
  });

  it('says so when no worker is online', async () => {
    stubApi(
      runCountsApi({}),
      runListApi([[]]),
      workersApi({ items: [worker({ name: 'w-old', state: 'LOST' })] }),
      catalogApi(),
    );
    renderApp('/');
    expect(await screen.findByText(/No worker is online/)).toBeVisible();
  });

  it('shows a failed part as an error, never as zeros, and keeps the rest', async () => {
    vi.useRealTimers();
    stubApi(
      ({ path }) =>
        path.includes('/run-counts') ? problem(404, 'not-found', 'Not found') : undefined,
      runListApi([[runSummary()]]),
      workers,
      catalogApi(),
    );
    renderApp('/');
    const counts = await screen.findByRole('region', { name: 'Run status · last 24 h' });
    expect(await within(counts).findByRole('alert')).toHaveTextContent('Not found');
    expect(within(counts).queryByRole('listitem')).toBeNull();
    expect(await screen.findByRole('list', { name: 'Recent runs' })).toBeVisible();
  });

  it('says when its numbers could not be refreshed, and from when they are', async () => {
    vi.useRealTimers();
    let fail = false;
    stubApi(
      ({ path }) =>
        fail && path.includes('/run-counts')
          ? problem(503, 'internal-error', 'Unavailable')
          : undefined,
      runCountsApi({ PASSED: 3 }),
      runListApi([[]]),
      workers,
      catalogApi(),
    );
    const { client } = renderApp('/');
    await screen.findByLabelText('3 PASSED runs in the last 24 hours');
    fail = true;
    await client.refetchQueries({ queryKey: ['run-counts'] });
    const alert = await screen.findByRole('alert', {}, { timeout: 8_000 });
    expect(alert).toHaveTextContent('Could not refresh. What you see was loaded at');
    expect(screen.getByLabelText('3 PASSED runs in the last 24 hours')).toBeVisible();
  }, 10_000);

  it('has no serious or critical axe violations', async () => {
    stubApi(
      runCountsApi({ FAILED: 1 }),
      runListApi([[runSummary({ status: 'FAILED' })]]),
      workers,
      catalogApi(),
    );
    renderApp('/');
    await screen.findByRole('list', { name: 'Recent runs' });
    await screen.findByRole('img', { name: '2 of 3 sessions in use' });
    await expectNoSeriousA11yViolations();
  });
});

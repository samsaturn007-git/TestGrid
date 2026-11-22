import { act, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WORKERS_REFRESH_MS } from '../api/queries.ts';
import { catalogApi, problem, stubApi, worker, workersApi } from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';

// Component tests against a fake API (mocked, not live).

const registry = {
  items: [
    worker({ name: 'worker-bee2be453300', activeAttempts: 1 }),
    worker({ name: 'worker-draining', state: 'DRAINING', lastSeenAt: '2026-11-22T11:59:57Z' }),
    worker({ name: 'worker-gone', state: 'LOST', lastSeenAt: '2026-11-22T11:28:00Z' }),
  ],
  globalMaxSessions: 3,
  activeSessions: 1,
  dispatchedUnclaimed: 1,
};

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('workers page (PRD-05 FR-2, T6)', () => {
  it('shows capacity from the API: sessions against the cap and dispatched work', async () => {
    stubApi(workersApi(registry), catalogApi());
    renderApp('/workers');
    const capacity = await screen.findByRole('region', { name: 'Capacity' });
    expect(
      within(capacity).getByRole('img', {
        name: '1 of 3 sessions in use, 1 dispatched and not yet claimed',
      }),
    ).toBeVisible();
    expect(
      within(capacity).getByText('Dispatched, not yet claimed').parentElement,
    ).toHaveTextContent('1');
    const byState = within(capacity).getByText('Workers by state').parentElement as HTMLElement;
    expect(
      within(byState).getByRole('img', { name: 'Worker state: ACTIVE' }).closest('div'),
    ).toHaveTextContent('1');
    expect(
      within(byState).getByRole('img', { name: 'Worker state: LOST' }).closest('div'),
    ).toHaveTextContent('1');
    expect(screen.getByText(/2 online · 1 LOST in the last hour/)).toBeVisible();
  });

  it('lists live workers first, then those LOST in the last hour, with state, slots and times', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-11-22T12:00:00Z'));
    stubApi(workersApi(registry), catalogApi());
    renderApp('/workers');

    const table = await screen.findByRole('table', { name: /Workers, live first/ });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((r) => r.querySelector('th')?.textContent)).toEqual([
      expect.stringContaining('worker-bee2be453300'),
      expect.stringContaining('worker-draining'),
      'LOST in the last hour',
      expect.stringContaining('worker-gone'),
    ]);
    const first = within(rows[0] as HTMLElement);
    expect(first.getByRole('img', { name: 'Worker state: ACTIVE' })).toBeVisible();
    expect(first.getByText('running 1 attempt')).toBeVisible();
    expect(first.getByText('1 / 1')).toBeVisible();
    const seen = rows[0]?.querySelectorAll('time')[1];
    expect(seen).toHaveTextContent('5 s ago');
    expect(seen).toHaveAttribute('datetime', '2026-11-22T11:59:55Z');
    expect(within(rows[3] as HTMLElement).getByText('32 min ago')).toBeVisible();

    // "Time since last seen" ticks on its own between refreshes.
    await act(() => vi.advanceTimersByTimeAsync(3_000));
    expect(seen).toHaveTextContent('8 s ago');
  });

  it('refreshes the registry every 10 s', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const api = stubApi(workersApi(registry), catalogApi());
    renderApp('/workers');
    await screen.findByRole('table', { name: /Workers/ });
    const calls = () => api.requests.filter((r) => r.path === '/workers').length;
    const before = calls();
    await act(() => vi.advanceTimersByTimeAsync(WORKERS_REFRESH_MS + 100));
    expect(calls()).toBe(before + 1);
  });

  it('says how workers appear when none has registered', async () => {
    stubApi(workersApi({ items: [] }), catalogApi());
    renderApp('/workers');
    expect(await screen.findByRole('heading', { name: 'No worker has registered' })).toBeVisible();
  });

  it('shows the Problem Details when the registry cannot be read', async () => {
    stubApi(
      ({ path }) =>
        path === '/workers' ? problem(500, 'internal-error', 'Internal error') : undefined,
      catalogApi(),
    );
    renderApp('/workers');
    expect(await screen.findByRole('alert', {}, { timeout: 8_000 })).toHaveTextContent(
      'Internal error',
    );
    expect(screen.queryByRole('region', { name: 'Capacity' })).toBeNull();
  }, 10_000);

  it('has no serious or critical axe violations', async () => {
    stubApi(workersApi(registry), catalogApi());
    renderApp('/workers');
    await screen.findByRole('table', { name: /Workers/ });
    await expectNoSeriousA11yViolations();
  });
});

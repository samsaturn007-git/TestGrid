import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CaseHistoryResult, CaseHistoryView, CaseStatus } from '../api/types.ts';
import { catalogApi, json, problem, stubApi, type StubHandler } from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';
import { sparklinePoints } from './CaseHistoryPage.tsx';

// PRD-07 FR-5 component tests against a fake API (mocked, not live).

afterEach(() => {
  vi.unstubAllGlobals();
});

let counter = 0;
function result(status: CaseStatus, durationMs: number, minutesAgo: number): CaseHistoryResult {
  counter += 1;
  const id = `00000000-0000-4000-8000-${String(counter).padStart(12, '0')}`;
  return {
    runId: `11111111-0000-4000-8000-${String(counter).padStart(12, '0')}`,
    runStatus: status === 'FAILED' ? 'FAILED' : 'PASSED',
    trigger: 'MANUAL',
    branch: 'main',
    commitSha: null,
    targetId: id,
    attemptId: id,
    attemptNo: 1,
    status,
    durationMs,
    retryCount: status === 'FLAKY' ? 1 : 0,
    errorClass: status === 'FAILED' ? 'ASSERTION' : null,
    fingerprintId: null,
    finishedAt: new Date(Date.UTC(2026, 10, 24, 12, 0) - minutesAgo * 60_000).toISOString(),
  };
}

// Newest first, as the API sends it: P F P F P F from oldest to newest on chromium.
const chromium = ['FAILED', 'PASSED', 'FAILED', 'PASSED', 'FAILED', 'PASSED'].map((s, i) =>
  result(s as CaseStatus, 1500 + 100 * i, i),
);

const view: CaseHistoryView = {
  caseKey: 'checkout-happy-path',
  suiteKey: 'checkout-smoke',
  title: 'Checkout completes with a valid card',
  engines: [
    {
      engine: 'chromium',
      health: {
        label: 'FLAKY',
        reason: '5 flips in 5 transitions (1.00 ≥ 0.20)',
        windowSize: 20,
        observed: 6,
        passCount: 3,
        failCount: 3,
        flakyCount: 0,
        flipCount: 5,
        flipRate: 1,
        recentOutcomes: 'PFPFPF',
        computedAt: '2026-11-24T12:00:00Z',
      },
      results: chromium,
    },
    {
      engine: 'firefox',
      health: {
        label: 'INSUFFICIENT_DATA',
        reason: '2 results; a label needs at least 5',
        windowSize: 20,
        observed: 2,
        passCount: 2,
        failCount: 0,
        flakyCount: 0,
        flipCount: 0,
        flipRate: 0,
        recentOutcomes: 'PP',
        computedAt: '2026-11-24T12:00:00Z',
      },
      results: [result('PASSED', 900, 1), result('SKIPPED', 0, 2), result('PASSED', 800, 3)],
    },
    { engine: 'webkit', health: null, results: [] },
  ],
};

function historyApi(body: CaseHistoryView | Response = view): StubHandler {
  return ({ path }) =>
    path.startsWith('/cases/checkout-happy-path/history?')
      ? body instanceof Response
        ? body
        : json(body)
      : undefined;
}

describe('case history page (PRD-07 FR-5, T3)', () => {
  it('asks the API for this suite and the last 20 results', async () => {
    const { requests } = stubApi(catalogApi(), historyApi());
    renderApp('/cases/checkout-happy-path?suiteKey=checkout-smoke');
    await screen.findByRole('region', { name: 'chromium history' });
    expect(requests.map((r) => r.path)).toContain(
      '/cases/checkout-happy-path/history?suiteKey=checkout-smoke&limit=20',
    );
    expect(
      screen.getByRole('heading', { name: 'Case history · checkout-happy-path' }),
    ).toBeVisible();
    expect(screen.getByText('Checkout completes with a valid card · checkout-smoke')).toBeVisible();
  });

  it('shows each label with its reason, never a bare label', async () => {
    stubApi(catalogApi(), historyApi());
    renderApp('/cases/checkout-happy-path?suiteKey=checkout-smoke');
    const chromiumRow = await screen.findByRole('region', { name: 'chromium history' });
    expect(within(chromiumRow).getByText('FLAKY')).toBeVisible();
    expect(
      within(chromiumRow).getByText('5 flips in 5 transitions (1.00 ≥ 0.20) · last 6 of 20'),
    ).toBeVisible();
    const firefoxRow = screen.getByRole('region', { name: 'firefox history' });
    expect(within(firefoxRow).getByText('INSUFFICIENT_DATA')).toHaveAttribute(
      'data-label',
      'INSUFFICIENT_DATA',
    );
    const webkitRow = screen.getByRole('region', { name: 'webkit history' });
    expect(within(webkitRow).getByText('No results yet on this engine.')).toBeVisible();
    expect(within(webkitRow).queryByRole('list')).toBeNull();
  });

  it('draws the strip oldest first with a link to each run and dashed squares for the rest', async () => {
    stubApi(catalogApi(), historyApi());
    renderApp('/cases/checkout-happy-path?suiteKey=checkout-smoke');
    const strip = await screen.findByRole('list', {
      name: 'Last 6 results on chromium, oldest first',
    });
    const links = within(strip).getAllByRole('link');
    expect(links.map((l) => l.getAttribute('aria-label')?.split(' on ')[0])).toEqual([
      'PASSED',
      'FAILED',
      'PASSED',
      'FAILED',
      'PASSED',
      'FAILED',
    ]);
    expect(links.at(-1)).toHaveAttribute('href', `/runs/${chromium[0]?.runId}`);
    expect(links.at(-1)).toHaveTextContent('×');
    // 20 squares in all: 14 placeholders, hidden from assistive technology.
    expect(strip.querySelectorAll('li')).toHaveLength(20);
    expect(strip.querySelectorAll('li[aria-hidden="true"]')).toHaveLength(14);
  });

  it('describes the duration trend in words and leaves skipped results out of it', async () => {
    stubApi(catalogApi(), historyApi());
    renderApp('/cases/checkout-happy-path?suiteKey=checkout-smoke');
    expect(
      await screen.findByRole('img', {
        name: 'Duration over the last 6 results on chromium: shortest 1.5 s, longest 2.0 s, latest 1.5 s',
      }),
    ).toBeVisible();
    expect(
      screen.getByRole('img', { name: /Duration over the last 2 results on firefox/ }),
    ).toBeVisible();
    const points = sparklinePoints(view.engines[1]?.results ?? []).split(' ');
    expect(points).toHaveLength(2);
    // Oldest (800 ms) at the left and lowest; newest (900 ms) at the right and highest.
    expect(points[0]).toBe('2.0,26.0');
    expect(points[1]).toBe('148.0,2.0');
  });

  it('explains a missing suite key instead of asking the API', async () => {
    const { requests } = stubApi(catalogApi(), historyApi());
    renderApp('/cases/checkout-happy-path');
    expect(await screen.findByText('Which suite?')).toBeVisible();
    expect(requests.some((r) => r.path.startsWith('/cases/'))).toBe(false);
  });

  it('shows the Problem when the case is unknown', async () => {
    stubApi(catalogApi(), historyApi(problem(404, 'not-found', 'Not found')));
    renderApp('/cases/checkout-happy-path?suiteKey=checkout-smoke');
    expect(await screen.findByRole('alert')).toHaveTextContent(/Not found/);
  });

  it('has no serious accessibility violations', async () => {
    stubApi(catalogApi(), historyApi());
    renderApp('/cases/checkout-happy-path?suiteKey=checkout-smoke');
    await screen.findByRole('region', { name: 'chromium history' });
    await expectNoSeriousA11yViolations();
  });
});

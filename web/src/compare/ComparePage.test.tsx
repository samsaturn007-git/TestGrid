import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ComparisonCategory, ComparisonRow, RunComparisonView } from '../api/types.ts';
import { catalogApi, json, problem, stubApi, type StubHandler } from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';

// PRD-07 FR-6 component tests against a fake API (mocked, not live).

afterEach(() => {
  vi.unstubAllGlobals();
});

const BASE = 'd48c0b13-0000-4000-8000-000000000001';
const HEAD = '355f5da2-0000-4000-8000-000000000002';
const ORDER: ComparisonCategory[] = [
  'NEW_FAILURE',
  'FIXED',
  'STILL_FAILING',
  'FLAKY_IN_HEAD',
  'ADDED',
  'REMOVED',
  'NOT_COMPARABLE',
  'STILL_PASSING',
];

function row(
  overrides: Partial<ComparisonRow> & Pick<ComparisonRow, 'caseKey' | 'category'>,
): ComparisonRow {
  return {
    engine: 'chromium',
    baseStatus: 'PASSED',
    headStatus: 'PASSED',
    baseDurationMs: 1000,
    headDurationMs: 1100,
    durationRegression: false,
    baseFingerprintId: null,
    headFingerprintId: null,
    fingerprintChanged: false,
    headHealthLabel: null,
    reason: null,
    ...overrides,
  };
}

const rows: ComparisonRow[] = [
  ...(['chromium', 'firefox', 'webkit'] as const).map((engine) =>
    row({ caseKey: 'cart-total', category: 'NEW_FAILURE', engine, headStatus: 'FAILED' }),
  ),
  row({
    caseKey: 'card-declined',
    category: 'STILL_FAILING',
    baseStatus: 'FAILED',
    headStatus: 'FAILED',
    fingerprintChanged: true,
    reason: 'the failure signature changed',
  }),
  row({
    caseKey: 'checkout-happy-path',
    category: 'STILL_PASSING',
    engine: 'firefox',
    baseDurationMs: 1700,
    headDurationMs: 3400,
    durationRegression: true,
  }),
  row({ caseKey: 'add-to-cart', category: 'STILL_PASSING' }),
];

function comparison(overrides: Partial<RunComparisonView> = {}): RunComparisonView {
  const counts = ORDER.map((category) => ({
    category,
    count: rows.filter((r) => r.category === category).length,
  }));
  const ref = {
    status: 'PASSED' as const,
    suiteKey: 'checkout-smoke',
    revision: '29d6afdd879b',
    branch: 'main',
    commitSha: '8b21e4d0c9aa',
    fixtureMode: null,
    fixtureSeed: null,
    trigger: 'MANUAL' as const,
    queuedAt: '2026-11-24T10:00:00Z',
    finishedAt: '2026-11-24T10:01:00Z',
  };
  return {
    base: { ...ref, id: BASE },
    head: { ...ref, id: HEAD, status: 'FAILED', fixtureMode: 'PRODUCT_DEFECT', fixtureSeed: 42 },
    baseIsLastGreen: true,
    sameRevision: true,
    thresholds: { regressionRatio: 1.5, regressionMinDeltaMs: 500 },
    counts,
    durationRegressions: 1,
    rows,
    ...overrides,
  };
}

function compareApi(body: RunComparisonView | Response = comparison()): StubHandler {
  return ({ path }) =>
    path.startsWith('/runs/compare?') ? (body instanceof Response ? body : json(body)) : undefined;
}

describe('compare page (PRD-07 FR-6, T4)', () => {
  it('asks for the last green comparison and shows both runs', async () => {
    const { requests } = stubApi(catalogApi(), compareApi());
    renderApp(`/compare?base=last-green&head=${HEAD}`);
    const runs = await screen.findByRole('region', { name: 'Runs compared' });
    expect(requests.map((r) => r.path)).toContain(`/runs/compare?base=last-green&head=${HEAD}`);
    expect(within(runs).getByText('Base · last green on main')).toBeVisible();
    expect(within(runs).getByRole('link', { name: 'd48c0b13' })).toHaveAttribute(
      'href',
      `/runs/${BASE}`,
    );
    expect(within(runs).getByText('PRODUCT_DEFECT · 42')).toBeVisible();
    expect(within(runs).getByRole('img', { name: 'Run status: FAILED' })).toBeVisible();
  });

  it('groups rows by category with changes open and unchanged cases folded', async () => {
    stubApi(catalogApi(), compareApi());
    renderApp(`/compare?base=${BASE}&head=${HEAD}`);
    const newFailures = await screen.findByRole('list', { name: 'NEW_FAILURE rows' });
    const links = within(newFailures).getAllByRole('link', { name: 'cart-total' });
    expect(links).toHaveLength(3);
    expect(links[0]).toHaveAttribute('href', '/cases/cart-total?suiteKey=checkout-smoke');
    expect(within(newFailures).getAllByText('PASSED → FAILED')).toHaveLength(3);
    const groups = screen
      .getByRole('region', { name: 'Changes by category' })
      .querySelectorAll('details');
    expect([...groups].map((d) => d.querySelector('summary span')?.textContent)).toEqual(ORDER);
    const open = [...groups]
      .filter((d) => d.open)
      .map((d) => d.querySelector('summary span')?.textContent);
    expect(open).toEqual(['NEW_FAILURE', 'STILL_FAILING']);
  });

  it('calls out a changed fingerprint and a duration regression', async () => {
    stubApi(catalogApi(), compareApi());
    renderApp(`/compare?base=${BASE}&head=${HEAD}`);
    const stillFailing = await screen.findByRole('list', { name: 'STILL_FAILING rows' });
    expect(within(stillFailing).getByText('fingerprint changed')).toBeVisible();
    expect(within(stillFailing).getByText('the failure signature changed')).toBeVisible();
    const heading = screen.getByRole('heading', { name: /Duration regressions/ });
    expect(heading).toHaveTextContent('head slower than base × 1.5 and base + 500 ms');
    expect(screen.getByText('▲ firefox · checkout-happy-path · 1.7 s → 3.4 s')).toBeVisible();
  });

  it('says when the suite revisions differ', async () => {
    stubApi(
      catalogApi(),
      compareApi(
        comparison({
          sameRevision: false,
          head: { ...comparison().head, revision: 'b4e07c9a1d52' },
        }),
      ),
    );
    renderApp(`/compare?base=${BASE}&head=${HEAD}`);
    expect(await screen.findByRole('note')).toHaveTextContent(
      'The suite revisions differ (29d6afdd879b → b4e07c9a1d52)',
    );
  });

  it('asks for two runs instead of calling the API without them', async () => {
    const { requests } = stubApi(catalogApi(), compareApi());
    renderApp('/compare?head=' + HEAD);
    expect(await screen.findByText('Choose two runs')).toBeVisible();
    expect(requests.some((r) => r.path.startsWith('/runs/compare'))).toBe(false);
  });

  it('shows why there is no last green run', async () => {
    stubApi(
      catalogApi(),
      compareApi(
        problem(404, 'not-found', 'Not found', {
          detail: 'no PASSED run of checkout-smoke on branch main was queued before run x',
        }),
      ),
    );
    renderApp(`/compare?base=last-green&head=${HEAD}`);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'no PASSED run of checkout-smoke on branch main',
    );
  });

  it('has no serious accessibility violations', async () => {
    stubApi(catalogApi(), compareApi());
    renderApp(`/compare?base=${BASE}&head=${HEAD}`);
    await screen.findByRole('region', { name: 'Runs compared' });
    await expectNoSeriousA11yViolations();
  });
});

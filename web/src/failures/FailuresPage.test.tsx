import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PageFailureGroupSummary } from '../api/types.ts';
import { catalogApi, json, stubApi, type StubHandler } from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';
import { GROUP_ID, summary } from './fixtures.ts';

// PRD-07 FR-7 component tests against a fake API (mocked, not live).

afterEach(() => {
  vi.unstubAllGlobals();
});

const DRIFT_ID = '7f000000-0000-4000-8000-000000000002';
const LAUNCH_ID = '7f000000-0000-4000-8000-000000000003';

const firstPage: PageFailureGroupSummary = {
  items: [
    summary(),
    summary({
      id: DRIFT_ID,
      category: 'PRODUCT_SUSPECTED',
      ruleCategory: 'TEST_BROKEN',
      ruleId: 'R1',
      overridden: true,
      errorClass: 'SELECTOR_NOT_FOUND',
      confidence: 0.82,
      normalisedMessage: 'TimeoutError: locator.click: Timeout <N>ms exceeded.',
      occurrenceCount: 2,
      caseKeys: ['card-declined', 'checkout-happy-path'],
    }),
  ],
  nextCursor: 'cursor-2',
};

const secondPage: PageFailureGroupSummary = {
  items: [
    summary({
      id: LAUNCH_ID,
      category: 'ENVIRONMENT',
      ruleCategory: 'ENVIRONMENT',
      ruleId: 'R3',
      errorClass: 'BROWSER_LAUNCH',
      confidence: 0.85,
      normalisedMessage: 'browserType.launch failed',
      occurrenceCount: 1,
      engines: ['webkit'],
      caseKeys: [],
      wholeTarget: true,
    }),
  ],
  nextCursor: null,
};

function listApi(pages: Record<string, PageFailureGroupSummary> = {}): StubHandler {
  return ({ path }) => {
    if (!path.startsWith('/failure-groups')) return undefined;
    if (path.includes('cursor=cursor-2')) return json(secondPage);
    const filtered = Object.entries(pages).find(([query]) => path.includes(query));
    return json(filtered ? filtered[1] : firstPage);
  };
}

describe('failures page (PRD-07 FR-7, T5)', () => {
  it('lists groups with their likely category, confidence, cases and engines', async () => {
    stubApi(catalogApi(), listApi());
    renderApp('/failures');
    const list = await screen.findByRole('list', { name: 'Failure groups' });
    const links = within(list).getAllByRole('link');
    expect(links).toHaveLength(2);
    expect(links[0]).toHaveAttribute('href', `/failures/${GROUP_ID}`);
    expect(
      within(links[0] as HTMLElement).getByRole('img', {
        name: 'Failure category: PRODUCT_SUSPECTED',
      }),
    ).toBeVisible();
    expect(links[0]).toHaveTextContent('confidence 0.74');
    expect(links[0]).toHaveTextContent('cart-total · ASSERTION');
    expect(links[0]).toHaveTextContent('3 occurrences');
    expect(
      within(links[0] as HTMLElement).getByLabelText('Engines: chromium, firefox, webkit'),
    ).toBeVisible();
    expect(links[1]).toHaveTextContent('confidence 0.82 · overridden');
    expect(links[1]).toHaveTextContent('card-declined, checkout-happy-path · SELECTOR_NOT_FOUND');
  });

  it('loads the next page with the cursor and shows a whole-target group', async () => {
    const { requests } = stubApi(catalogApi(), listApi());
    const { user } = renderApp('/failures');
    await screen.findByRole('list', { name: 'Failure groups' });
    await user.click(screen.getByRole('button', { name: 'Load more groups' }));
    expect(await screen.findByText('whole target · BROWSER_LAUNCH')).toBeVisible();
    expect(requests.map((r) => r.path)).toContain('/failure-groups?cursor=cursor-2');
    expect(screen.queryByRole('button', { name: 'Load more groups' })).toBeNull();
  });

  it('sends the chosen filters and keeps them in the address', async () => {
    const { requests } = stubApi(
      catalogApi(),
      listApi({ 'category=TEST_BROKEN': { items: [], nextCursor: null } }),
    );
    const { user, router } = renderApp('/failures');
    await screen.findByRole('list', { name: 'Failure groups' });
    await user.selectOptions(screen.getByLabelText('Category'), 'TEST_BROKEN');
    expect(await screen.findByText('No group matches these filters')).toBeVisible();
    expect(router.state.location.search).toBe('?category=TEST_BROKEN');
    await user.selectOptions(screen.getByLabelText('Engine'), 'firefox');
    await user.selectOptions(screen.getByLabelText('Last seen'), '7d');
    const last = requests.at(-1)?.path ?? '';
    expect(last).toMatch(
      /^\/failure-groups\?category=TEST_BROKEN&engine=firefox&from=\d{4}-\d\d-\d\dT/,
    );
  });

  it('explains an empty project', async () => {
    stubApi(catalogApi(), listApi({ 'failure-groups': { items: [], nextCursor: null } }));
    renderApp('/failures');
    expect(await screen.findByText('No failures recorded yet')).toBeVisible();
  });

  it('has no serious accessibility violations', async () => {
    stubApi(catalogApi(), listApi());
    renderApp('/failures');
    await screen.findByRole('list', { name: 'Failure groups' });
    await expectNoSeriousA11yViolations();
  });
});

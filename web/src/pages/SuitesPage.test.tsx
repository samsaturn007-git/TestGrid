import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CATALOG,
  CHECKOUT,
  CHECKOUT_CASES,
  catalogApi,
  problem,
  PROJECT_ID,
  stubApi,
  suite,
} from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';

// Component tests against a fake API (mocked, not live).

const PATH = `/projects/${PROJECT_ID}/suites`;

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('suites page (PRD-05 FR-2, T2)', () => {
  it('shows one card per suite with case count, timeout, revision and engines from the API', async () => {
    stubApi(catalogApi());
    renderApp(PATH);

    const card = (await screen.findByRole('heading', { level: 2, name: 'Checkout smoke' })).closest(
      'article',
    );
    expect(card).not.toBeNull();
    const c = within(card as HTMLElement);
    expect(card).toHaveAccessibleName('Checkout smoke');
    expect(c.getByText('checkout-smoke')).toBeVisible();
    expect(c.getByText('Cases').nextElementSibling).toHaveTextContent('6');
    expect(c.getByText('Timeout').nextElementSibling).toHaveTextContent('120 s');
    expect(c.getByText('Revision').nextElementSibling).toHaveTextContent(CHECKOUT.revision);
    expect(
      within(c.getByRole('list', { name: 'Engines' }))
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['Cchromium', 'Ffirefox', 'Wwebkit']);

    expect(screen.getAllByRole('article')).toHaveLength(2);
    expect(screen.getByText(/2 registered suites in Local project/)).toBeVisible();
    expect(screen.getByText(/can't be edited here/)).toBeVisible();
  });

  it('loads a suite’s cases only when its card is expanded', async () => {
    const api = stubApi(catalogApi());
    const { user } = renderApp(PATH);
    const toggle = await screen.findByRole('button', { name: 'Show 6 cases' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(api.requests.some((r) => r.path.startsWith('/suites/'))).toBe(false);

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(toggle).toHaveAccessibleName('Hide cases');
    const table = await screen.findByRole('table', { name: 'Cases in Checkout smoke' });
    expect(document.getElementById(toggle.getAttribute('aria-controls') ?? '')).toContainElement(
      table,
    );
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((r) => within(r).getAllByRole('cell')[1]?.textContent)).toEqual(
      CHECKOUT_CASES.map((c) => c.caseKey),
    );
    expect(within(rows[2] as HTMLElement).getByText('critical')).toBeVisible();
    expect(api.requests.filter((r) => r.path === `/suites/${CHECKOUT.id}`)).toHaveLength(1);

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('shows a case-load failure inside the card', async () => {
    stubApi(catalogApi({ details: {} }));
    const { user } = renderApp(PATH);
    await user.click(await screen.findByRole('button', { name: 'Show 12 cases' }));
    const card = screen.getByRole('article', { name: CATALOG.displayName });
    expect(await within(card).findByRole('alert')).toHaveTextContent('Not found');
  });

  it('offers no Run button for a suite the manifest disables', async () => {
    const off = suite({ id: 'off', suiteKey: 'retired', displayName: 'Retired', enabled: false });
    stubApi(catalogApi({ suites: [CHECKOUT, off] }));
    renderApp(PATH);
    const card = await screen.findByRole('article', { name: 'Retired' });
    expect(within(card).getByText('Disabled in the manifest')).toBeVisible();
    expect(within(card).queryByRole('button', { name: /^Run/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Run Checkout smoke' })).toBeVisible();
  });

  it('explains how to add a suite when the manifest has none', async () => {
    stubApi(catalogApi({ suites: [] }));
    renderApp(PATH);
    expect(await screen.findByRole('heading', { name: 'No suites registered' })).toBeVisible();
    expect(screen.getByText('worker/manifest/suites.json')).toBeVisible();
  });

  it('shows the Problem Details for a project the caller cannot see', async () => {
    const other = '00000000-0000-4000-8000-0000000000ff';
    stubApi(
      ({ path }) =>
        path === `/projects/${other}/suites`
          ? problem(404, 'not-found', 'Not found', { detail: `project ${other} not found` })
          : undefined,
      catalogApi(),
    );
    renderApp(`/projects/${other}/suites`);
    expect(await screen.findByRole('alert')).toHaveTextContent(`project ${other} not found`);
  });

  it('has no serious or critical axe violations, with a card expanded', async () => {
    stubApi(catalogApi());
    const { user } = renderApp(PATH);
    await user.click(await screen.findByRole('button', { name: 'Show 6 cases' }));
    await screen.findByRole('table');
    await expectNoSeriousA11yViolations();
  });
});

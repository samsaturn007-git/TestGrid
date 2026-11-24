import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FailureGroupView } from '../api/types.ts';
import { catalogApi, json, problem, stubApi, type StubHandler } from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';
import { group, GROUP_ID, RUN_ID } from './fixtures.ts';

// PRD-07 FR-7 / FR-3 component tests against a fake API (mocked, not live).

afterEach(() => {
  vi.unstubAllGlobals();
});

function groupApi(
  body: FailureGroupView | Response = group(),
  put?: (b: unknown) => Response,
): StubHandler {
  return ({ method, path, body: sent }) => {
    if (path === `/failure-groups/${GROUP_ID}` && method === 'GET') {
      return body instanceof Response ? body : json(body);
    }
    if (path === `/failure-groups/${GROUP_ID}/override` && method === 'PUT' && put)
      return put(sent);
    return undefined;
  };
}

describe('failure group page (PRD-07 FR-7, T5)', () => {
  it('shows the likely category with the rule that gave it, never a bare label', async () => {
    stubApi(catalogApi(), groupApi());
    renderApp(`/failures/${GROUP_ID}`);
    const summary = await screen.findByRole('region', { name: 'Summary' });
    expect(
      within(summary).getByRole('img', { name: 'Failure category: PRODUCT_SUSPECTED' }),
    ).toBeVisible();
    expect(summary).toHaveTextContent(
      'Likely PRODUCT_SUSPECTED, by rule R5. A label from evidence, not proof',
    );
    expect(summary).toHaveTextContent('3 occurrences · 1 run · 3 engines · ASSERTION');
    const rules = screen.getByRole('list', { name: 'Rules tried, in order' });
    expect(
      within(rules)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([
      '–R1 did not match: error class ASSERTION is not [SELECTOR_NOT_FOUND]',
      '✓R5 matched: error class ASSERTION, seen in 3 attempts across 1 run(s)',
    ]);
    expect(
      screen.getByText(/confidence 0.70 base \+ 0.04 for occurrences − 0.00 disagreement = 0.74/),
    ).toBeVisible();
    expect(screen.getByText(/Received: "\$<N>.<N>"/)).toBeVisible();
  });

  it('lists occurrences with links to their run and case, artifacts and raw messages', async () => {
    stubApi(catalogApi(), groupApi());
    renderApp(`/failures/${GROUP_ID}`);
    const table = await screen.findByRole('table', { name: /Occurrences of this failure/ });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    const first = rows[0] as HTMLElement;
    expect(within(first).getByRole('link', { name: RUN_ID.slice(0, 8) })).toHaveAttribute(
      'href',
      `/runs/${RUN_ID}`,
    );
    expect(within(first).getByRole('link', { name: 'cart-total' })).toHaveAttribute(
      'href',
      '/cases/cart-total?suiteKey=checkout-smoke',
    );
    expect(within(first).getByRole('button', { name: 'download screenshot' })).toBeVisible();
    expect(within(first).getByText('trace PURGED')).toBeVisible();
    expect(within(first).getByText('message')).toBeVisible();
    const second = rows[1] as HTMLElement;
    expect(within(second).getByText('whole target')).toBeVisible();
    expect(second).toHaveTextContent('INFRA_ERROR · attempt 2');
    expect(within(second).getByText('none kept')).toBeVisible();
  });

  it('saves an override with its reason and shows the new category and audit entry', async () => {
    const overridden = group({
      category: 'TEST_BROKEN',
      override: {
        category: 'TEST_BROKEN',
        reason: 'fixture on purpose',
        by: 'local',
        at: '2026-11-24T12:00:00Z',
      },
      audit: [
        {
          id: '00000000-0000-4000-8000-0000000000e1',
          actor: 'local',
          action: 'FAILURE_CATEGORY_OVERRIDDEN',
          occurredAt: '2026-11-24T12:00:00Z',
          metadata: { from: 'PRODUCT_SUSPECTED', to: 'TEST_BROKEN', reason: 'fixture on purpose' },
        },
      ],
    });
    const { requests } = stubApi(
      catalogApi(),
      groupApi(group(), () => json(overridden)),
    );
    const { user } = renderApp(`/failures/${GROUP_ID}`);
    const save = await screen.findByRole('button', { name: 'Save override' });
    expect(save).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('New category'), 'TEST_BROKEN');
    await user.type(screen.getByLabelText(/Reason/), 'fixture on purpose');
    await user.click(save);
    expect(await screen.findByRole('status')).toHaveTextContent('Saved.');
    expect(requests.find((r) => r.method === 'PUT')?.body).toEqual({
      category: 'TEST_BROKEN',
      reason: 'fixture on purpose',
    });
    const summary = screen.getByRole('region', { name: 'Summary' });
    expect(summary).toHaveTextContent('Overridden to TEST_BROKEN by local');
    expect(summary).toHaveTextContent('The rule says PRODUCT_SUSPECTED.');
    expect(
      screen.getByText(/local · PRODUCT_SUSPECTED → TEST_BROKEN · “fixture on purpose”/),
    ).toBeVisible();
  });

  it('shows the API problem when an override is refused', async () => {
    stubApi(
      catalogApi(),
      groupApi(group(), () =>
        problem(400, 'validation-failed', 'Request validation failed', {
          detail: 'Request validation failed',
          errors: [{ field: 'reason', message: 'must be 1 … 500 characters' }],
        }),
      ),
    );
    const { user } = renderApp(`/failures/${GROUP_ID}`);
    await user.type(await screen.findByLabelText(/Reason/), 'x');
    await user.click(screen.getByRole('button', { name: 'Save override' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'reason: must be 1 … 500 characters',
    );
  });

  it('shows the Problem for an unknown group', async () => {
    stubApi(catalogApi(), groupApi(problem(404, 'not-found', 'Not found')));
    renderApp(`/failures/${GROUP_ID}`);
    expect(await screen.findByRole('alert')).toHaveTextContent('Not found');
  });

  it('has no serious accessibility violations', async () => {
    stubApi(catalogApi(), groupApi());
    renderApp(`/failures/${GROUP_ID}`);
    await screen.findByRole('region', { name: 'Summary' });
    await expectNoSeriousA11yViolations();
  });
});

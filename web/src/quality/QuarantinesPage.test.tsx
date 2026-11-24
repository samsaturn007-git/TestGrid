import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CreateQuarantineRequest } from '../api/types.ts';
import { catalogApi, json, PROJECT_ID, problem, stubApi, type StubHandler } from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';
import { quarantine, quarantineList, SERVER_NOW } from '../test/qualityFixtures.ts';
import { formatRemaining } from './QuarantinesPage.tsx';

// PRD-08 FR-3 / FR-7 quarantines page against a fake API (mocked, not live).

afterEach(() => {
  vi.unstubAllGlobals();
});

const LIST = `/projects/${PROJECT_ID}/quarantines`;

function quarantineApi(
  post?: (body: CreateQuarantineRequest) => Response,
  list = quarantineList(),
): StubHandler {
  return ({ method, path, body }) => {
    if (method === 'GET' && path === LIST) return json(list);
    if (method === 'POST' && path === LIST && post) return post(body as CreateQuarantineRequest);
    if (method === 'DELETE' && path.startsWith('/quarantines/')) {
      return json(quarantine({ state: 'REVOKED', revokedAt: SERVER_NOW }));
    }
    return undefined;
  };
}

describe('quarantines page (PRD-08 FR-3, FR-7, T6)', () => {
  it('lists active quarantines with countdowns on the server clock', async () => {
    stubApi(catalogApi(), quarantineApi());
    renderApp('/quarantines');
    const list = await screen.findByRole('list', { name: 'Quarantines, newest first' });
    const rows = within(list).getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('checkout-happy-path');
    expect(rows[0]).toHaveTextContent('expires in 10 d 0 h');
    expect(rows[1]).toHaveTextContent('card-declined');
    expect(within(rows[1] as HTMLElement).getByText('expires in 1 d 6 h')).toHaveClass(
      'text-warning',
    );
    expect(screen.getByText(/2 of 5/).parentElement).toHaveTextContent(
      '2 of 5 active quarantines allowed by the policy · auto mode SUGGEST',
    );
    await expectNoSeriousA11yViolations();
  });

  it('shows expired ones on request', async () => {
    stubApi(catalogApi(), quarantineApi());
    const { user } = renderApp('/quarantines');
    await screen.findByRole('list', { name: 'Quarantines, newest first' });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Show' }), 'EXPIRED');
    const rows = within(
      screen.getByRole('list', { name: 'Quarantines, newest first' }),
    ).getAllByRole('listitem');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('apply-coupon');
    expect(rows[0]).toHaveTextContent('every engine');
    expect(rows[0]).toHaveTextContent('expired');
    expect(within(rows[0] as HTMLElement).queryByRole('button')).toBeNull();
  });

  it('quarantines a suggested FLAKY case with a reason and an expiry', async () => {
    const sent: CreateQuarantineRequest[] = [];
    stubApi(
      catalogApi(),
      quarantineApi((body) => {
        sent.push(body);
        return json(quarantine({ caseKey: body.caseKey, source: 'SUGGESTED' }), 201);
      }),
    );
    const { user } = renderApp('/quarantines');
    const card = await screen.findByRole('form', { name: 'Quarantine add-to-cart on webkit' });
    expect(card).toHaveTextContent('FLAKYadd-to-cart on webkit. Quarantine?');
    await user.type(within(card).getByRole('textbox', { name: /Reason/ }), 'known flake, TG-12');
    await user.selectOptions(within(card).getByRole('combobox', { name: 'Expires in' }), '14');
    await user.click(within(card).getByRole('button', { name: 'Quarantine' }));
    await waitFor(() => expect(sent).toHaveLength(1));
    const body = sent[0]!;
    expect(body).toMatchObject({
      caseKey: 'add-to-cart',
      engine: 'webkit',
      reason: 'known flake, TG-12',
      source: 'SUGGESTED',
    });
    // 14 days from the server's clock, give or take the test's own run time.
    const days = (Date.parse(body.expiresAt) - Date.parse(SERVER_NOW)) / 86_400_000;
    expect(days).toBeGreaterThan(13.99);
    expect(days).toBeLessThan(14.01);
  });

  it('a 30-day quarantine stays within the API limit', async () => {
    const sent: CreateQuarantineRequest[] = [];
    stubApi(
      catalogApi(),
      quarantineApi((body) => {
        sent.push(body);
        return json(quarantine(), 201);
      }),
    );
    const { user } = renderApp('/quarantines');
    const form = await screen.findByRole('form', { name: 'Quarantine a case' });
    await user.type(within(form).getByRole('textbox', { name: 'Case key' }), 'cart-total');
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Expires in' }), '30');
    await user.type(
      within(form).getByRole('textbox', { name: /Reason/ }),
      'payment provider outage',
    );
    await user.click(within(form).getByRole('button', { name: 'Quarantine' }));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).not.toHaveProperty('engine');
    const ms = Date.parse(sent[0]!.expiresAt) - Date.parse(SERVER_NOW);
    expect(ms).toBeLessThan(30 * 86_400_000);
    expect(ms).toBeGreaterThan(30 * 86_400_000 - 120_000);
    expect(await within(form).findByRole('status')).toHaveTextContent(/quarantined until/);
  });

  it('shows the cap refusal and disables creation when full', async () => {
    stubApi(
      catalogApi(),
      quarantineApi(() =>
        problem(409, 'quarantine-conflict', 'Quarantine refused', {
          detail:
            'cart-total is already quarantined by quarantine 00000000-0000-4000-8000-0000000000f1',
        }),
      ),
    );
    const { user } = renderApp('/quarantines');
    const form = await screen.findByRole('form', { name: 'Quarantine a case' });
    await user.type(within(form).getByRole('textbox', { name: 'Case key' }), 'cart-total');
    await user.type(within(form).getByRole('textbox', { name: /Reason/ }), 'x');
    await user.click(within(form).getByRole('button', { name: 'Quarantine' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('already quarantined');
  });

  it('disables quarantining at the cap', async () => {
    stubApi(catalogApi(), quarantineApi(undefined, quarantineList({ activeCount: 5 })));
    renderApp('/quarantines');
    const form = await screen.findByRole('form', { name: 'Quarantine a case' });
    expect(within(form).getByText(/cap is reached/)).toBeVisible();
    expect(within(form).getByRole('button', { name: 'Quarantine' })).toBeDisabled();
  });

  it('revokes a quarantine', async () => {
    const api = stubApi(catalogApi(), quarantineApi());
    const { user } = renderApp('/quarantines');
    await user.click(
      await screen.findByRole('button', {
        name: 'Revoke the quarantine of checkout-happy-path on chromium',
      }),
    );
    await waitFor(() => expect(api.requests.some((r) => r.method === 'DELETE')).toBe(true));
    expect(api.requests.find((r) => r.method === 'DELETE')?.path).toBe(
      '/quarantines/00000000-0000-4000-8000-0000000000f1',
    );
  });

  it('formats time left', () => {
    expect(formatRemaining(4 * 60_000)).toBe('4 min');
    expect(formatRemaining(5 * 3_600_000 + 12 * 60_000)).toBe('5 h 12 min');
    expect(formatRemaining(6 * 86_400_000 + 23 * 3_600_000)).toBe('6 d 23 h');
    expect(formatRemaining(-1)).toBe('0 min');
  });
});

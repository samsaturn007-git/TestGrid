import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PolicyUpdateRequest } from '../api/types.ts';
import { catalogApi, json, PROJECT_ID, problem, stubApi, type StubHandler } from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';
import { POLICY, policyView } from '../test/qualityFixtures.ts';

// PRD-08 FR-7 policy page against a fake API (mocked, not live).

afterEach(() => {
  vi.unstubAllGlobals();
});

const PATH = `/projects/${PROJECT_ID}/quality-policy`;

function policyApi(put?: (body: unknown) => Response): StubHandler {
  let current = policyView(2);
  return ({ method, path, body }) => {
    if (method === 'GET' && path === PATH) return json(current);
    if (method === 'GET' && path === `${PATH}?version=1`) return json(policyView(1));
    if (method === 'GET' && path === `${PATH}?version=9`)
      return problem(404, 'not-found', 'Not found', {
        detail: 'quality policy version 9 not found',
      });
    if (method === 'PUT' && path === PATH && put) {
      const answer = put(body);
      if (answer.ok) current = policyView(3, (body as PolicyUpdateRequest).policy);
      return answer;
    }
    return undefined;
  };
}

describe('policy page (PRD-08 FR-7, T6)', () => {
  it('shows the active version, its rules and the history', async () => {
    stubApi(catalogApi(), policyApi());
    renderApp(`/projects/${PROJECT_ID}/policy`);
    const rules = await screen.findByRole('region', { name: /Version 2/ });
    expect(rules).toHaveTextContent(
      'Required targets (missing or inconclusive → INDETERMINATE)local:chromium',
    );
    expect(rules).toHaveTextContent('Flaky rate allowed (above fails)0.2');
    expect(rules).toHaveTextContent('> 1.5× and > +500 ms · reported');
    const versions = screen.getByRole('list', { name: 'Policy versions, newest first' });
    expect(
      within(versions)
        .getAllByRole('link')
        .map((l) => l.textContent),
    ).toEqual(['v2', 'v1']);
    expect(within(versions).getByRole('link', { name: 'v1' })).toHaveAttribute(
      'href',
      `/projects/${PROJECT_ID}/policy?version=1`,
    );
    expect(screen.getByRole('heading', { name: 'Edit as v3' })).toBeVisible();
    await expectNoSeriousA11yViolations();
  });

  it('shows a past version read-only', async () => {
    stubApi(catalogApi(), policyApi());
    renderApp(`/projects/${PROJECT_ID}/policy?version=1`);
    expect(await screen.findByText('Version 1 is not the active policy.')).toBeVisible();
    expect(screen.queryByRole('form', { name: /Edit as/ })).toBeNull();
  });

  it('explains an unknown version', async () => {
    stubApi(catalogApi(), policyApi());
    renderApp(`/projects/${PROJECT_ID}/policy?version=9`);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'quality policy version 9 not found',
    );
    expect(screen.getByRole('link', { name: 'View the current version' })).toBeVisible();
  });

  it('saves the next version with its reason', async () => {
    const sent: unknown[] = [];
    stubApi(
      catalogApi(),
      policyApi((body) => {
        sent.push(body);
        return json(policyView(3));
      }),
    );
    const { user } = renderApp(`/projects/${PROJECT_ID}/policy`);
    const form = await screen.findByRole('form', { name: 'Edit as v3' });
    const save = within(form).getByRole('button', { name: 'Save as a new version' });
    expect(save).toBeDisabled();
    await user.click(within(form).getByRole('checkbox', { name: 'local:firefox' }));
    const failed = within(form).getByRole('spinbutton', { name: 'Failed cases allowed' });
    await user.clear(failed);
    await user.type(failed, '2');
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Auto mode' }), 'AUTO');
    await user.type(within(form).getByRole('textbox', { name: /Reason/ }), 'gate firefox too');
    await user.click(save);
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toEqual({
      reason: 'gate firefox too',
      policy: {
        ...POLICY,
        requiredTargets: ['local:chromium', 'local:firefox'],
        maxFailedCases: 2,
        quarantine: { ...POLICY.quarantine, autoMode: 'AUTO' },
      },
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Saved as version 3.');
  });

  it('needs a required target and shows the API refusal', async () => {
    stubApi(
      catalogApi(),
      policyApi(() =>
        problem(400, 'validation-failed', 'Request validation failed', {
          detail: 'Request validation failed',
          errors: [{ field: 'policy.maxFlakyRate', message: 'must be less than or equal to 1' }],
        }),
      ),
    );
    const { user } = renderApp(`/projects/${PROJECT_ID}/policy`);
    const form = await screen.findByRole('form', { name: 'Edit as v3' });
    await user.type(within(form).getByRole('textbox', { name: /Reason/ }), 'x');
    await user.click(within(form).getByRole('checkbox', { name: 'local:chromium' }));
    expect(within(form).getByText('Choose at least one required target.')).toBeVisible();
    expect(within(form).getByRole('button', { name: 'Save as a new version' })).toBeDisabled();
    await user.click(within(form).getByRole('checkbox', { name: 'local:chromium' }));
    await user.click(within(form).getByRole('button', { name: 'Save as a new version' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent(
      'policy.maxFlakyRate: must be less than or equal to 1.',
    );
  });
});

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CreateRunRequest, RunResponse } from '../api/types.ts';
import {
  CATALOG,
  CHECKOUT,
  catalogApi,
  json,
  problem,
  PROJECT_ID,
  stubApi,
  suite,
  type StubHandler,
} from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { renderApp } from '../test/render.tsx';

// Component tests against a fake API (mocked, not live). The live POST is checked on the
// running stack separately.

const RUN_ID = '00000000-0000-4000-8000-00000000c0de';
const RUNS_PATH = `/projects/${PROJECT_ID}/runs`;

function runResponse(request: CreateRunRequest, id = RUN_ID): RunResponse {
  return {
    id,
    projectId: PROJECT_ID,
    suiteId: CHECKOUT.id,
    suiteKey: request.suiteKey,
    suiteRevision: CHECKOUT.revision,
    status: 'QUEUED',
    trigger: 'MANUAL',
    idempotencyKey: request.idempotencyKey,
    fixture: request.fixture ?? null,
    sharding: { mode: 'NONE', shards: 1 },
    source: request.source ?? null,
    queuedAt: '2026-11-22T00:00:00Z',
    startedAt: null,
    finishedAt: null,
    cancelRequestedAt: null,
    targets: [],
    links: { self: `/api/v1/runs/${id}`, suite: `/api/v1/suites/${CHECKOUT.id}` },
  };
}

/** POST /projects/{id}/runs answered by `answer` for each successive call. */
function createRunApi(...answers: ((request: CreateRunRequest) => Response | Promise<Response>)[]) {
  let call = 0;
  const handler: StubHandler = ({ method, path, body }) => {
    if (method !== 'POST' || path !== RUNS_PATH) return undefined;
    const answer = answers[Math.min(call++, answers.length - 1)];
    return answer?.(body as CreateRunRequest);
  };
  return handler;
}

const accepted = (request: CreateRunRequest) => json(runResponse(request), 202);

async function openDialog(path = '/') {
  const app = renderApp(path);
  await app.user.click(await screen.findByRole('button', { name: 'New run' }));
  const dialog = await screen.findByRole('dialog', { name: 'New run' });
  await within(dialog).findByLabelText('Suite');
  return { ...app, dialog };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('new-run dialog (PRD-05 FR-2, T2)', () => {
  it('opens from the header as a labelled modal dialog with the runnable suites', async () => {
    const disabled = suite({
      id: 'd1',
      suiteKey: 'retired',
      displayName: 'Retired',
      enabled: false,
    });
    stubApi(catalogApi({ suites: [CHECKOUT, CATALOG, disabled] }));
    const { dialog } = await openDialog();

    expect(dialog).toHaveAttribute('open');
    const select = within(dialog).getByLabelText('Suite');
    expect(select).toHaveFocus();
    expect(
      within(select)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual([
      'Checkout smoke · checkout-smoke · 6 cases',
      'Catalog regression · catalog-regression · 12 cases',
    ]);
    for (const engine of ['chromium', 'firefox', 'webkit']) {
      expect(within(dialog).getByRole('checkbox', { name: engine })).toBeChecked();
    }
    expect(within(dialog).getByText('3 targets · each case runs on each engine')).toBeVisible();
  });

  it('shows fixture modes only when the API allows them, limited to the suite’s modes', async () => {
    stubApi(catalogApi({ fixturesAllowed: true }));
    const { dialog, user } = await openDialog();
    const group = within(dialog).getByRole('group', { name: /Fixture mode/ });
    expect(
      within(group)
        .getAllByRole('radio')
        .map((r) => (r as HTMLInputElement).value),
    ).toEqual(CHECKOUT.fixtureModes);
    expect(within(group).getByRole('radio', { name: /^PASS/ })).toBeChecked();

    await user.selectOptions(within(dialog).getByLabelText('Suite'), 'catalog-regression');
    expect(
      within(dialog)
        .getAllByRole('radio')
        .map((r) => (r as HTMLInputElement).value),
    ).toEqual(['PASS', 'PRODUCT_DEFECT']);
  });

  it('has no fixture section outside the local profile', async () => {
    stubApi(catalogApi({ fixturesAllowed: false }));
    const { dialog } = await openDialog();
    expect(within(dialog).queryByRole('group', { name: /Fixture mode/ })).toBeNull();
    expect(within(dialog).queryByLabelText('Seed')).toBeNull();
  });

  it('has no URL, script, regex or flag field: only suite, engines, fixture, seed, branch, SHA', async () => {
    stubApi(catalogApi());
    const { dialog } = await openDialog();
    // Every form control in the dialog, by name or by the field part of its generated id.
    const controls = [...dialog.querySelectorAll('input, select, textarea')].map(
      (el) => el.getAttribute('name') ?? el.id.split('-').pop(),
    );
    expect(new Set(controls)).toEqual(
      new Set(['suite', 'engines', 'fixtureMode', 'seed', 'branch', 'commitSha']),
    );
  });

  it('posts the run with an idempotency key, then opens the run detail', async () => {
    const api = stubApi(catalogApi(), createRunApi(accepted));
    const { dialog, user, router } = await openDialog();

    await user.click(within(dialog).getByRole('checkbox', { name: 'webkit' }));
    await user.click(within(dialog).getByRole('radio', { name: /^PRODUCT_DEFECT/ }));
    await user.type(within(dialog).getByLabelText(/Branch/), 'main');
    await user.click(within(dialog).getByRole('button', { name: 'Start run' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/runs/${RUN_ID}`));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(api.posts()).toHaveLength(1);
    const body = api.posts()[0]?.body as CreateRunRequest;
    expect(body).toEqual({
      suiteKey: 'checkout-smoke',
      targets: [
        { executor: 'LOCAL', engine: 'chromium' },
        { executor: 'LOCAL', engine: 'firefox' },
      ],
      trigger: 'MANUAL',
      fixture: { mode: 'PRODUCT_DEFECT', seed: 42 },
      source: { branch: 'main' },
      idempotencyKey: expect.stringMatching(/^[A-Za-z0-9._:-]{1,200}$/) as string,
    });
    expect(screen.getByRole('main')).toHaveFocus();
  });

  it('checks fields before posting: inline errors, an alert, focus on the first problem', async () => {
    const api = stubApi(catalogApi(), createRunApi(accepted));
    const { dialog, user } = await openDialog();

    for (const engine of ['chromium', 'firefox', 'webkit']) {
      await user.click(within(dialog).getByRole('checkbox', { name: engine }));
    }
    await user.type(within(dialog).getByLabelText(/Commit SHA/), 'zz91');
    await user.click(within(dialog).getByRole('button', { name: 'Start run' }));

    expect(within(dialog).getByRole('alert')).toHaveTextContent(
      '2 fields need attention before this run can start.',
    );
    const engines = within(dialog).getByRole('group', { name: /Engines/ });
    expect(engines).toHaveAccessibleDescription('Choose at least one engine.');
    const sha = within(dialog).getByLabelText(/Commit SHA/);
    expect(sha).toHaveAttribute('aria-invalid', 'true');
    expect(sha).toHaveAccessibleDescription(/7 to 64 lowercase hex/);
    expect(within(dialog).getByRole('checkbox', { name: 'chromium' })).toHaveFocus();
    expect(api.posts()).toHaveLength(0);
  });

  it('retries with the same key after a lost answer, and shows a replay as information', async () => {
    const api = stubApi(
      catalogApi(),
      createRunApi(
        () => Promise.reject(new TypeError('Failed to fetch')),
        (request) => json(runResponse(request), 200, { 'Idempotent-Replay': 'true' }),
      ),
    );
    const { dialog, user, router } = await openDialog();
    const start = within(dialog).getByRole('button', { name: 'Start run' });

    await user.click(start);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      /could not be reached.*same choices is safe/s,
    );
    await user.click(start);

    const status = await within(dialog).findByRole('status');
    expect(status).toHaveTextContent('This run already exists');
    const [first, second] = api.posts().map((p) => (p.body as CreateRunRequest).idempotencyKey);
    expect(second).toBe(first);
    expect(router.state.location.pathname).toBe('/');

    await user.click(within(status).getByRole('link', { name: /Open run 00000000/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/runs/${RUN_ID}`));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('uses a new key once the request changes, so an edit is never a 409', async () => {
    const api = stubApi(
      catalogApi(),
      createRunApi(() => problem(503, 'internal-error', 'Service unavailable'), accepted),
    );
    const { dialog, user } = await openDialog();
    await user.click(within(dialog).getByRole('button', { name: 'Start run' }));
    await within(dialog).findByRole('alert');

    await user.click(within(dialog).getByRole('checkbox', { name: 'webkit' }));
    await user.click(within(dialog).getByRole('button', { name: 'Start run' }));
    await waitFor(() => expect(api.posts()).toHaveLength(2));
    const [first, second] = api.posts().map((p) => (p.body as CreateRunRequest).idempotencyKey);
    expect(second).not.toBe(first);
  });

  it('puts API field errors next to their input and keeps the Problem title and trace ID', async () => {
    stubApi(
      catalogApi(),
      createRunApi(() =>
        problem(422, 'engine-not-allowed', 'Engine not allowed', {
          detail: 'suite checkout-smoke does not allow every requested engine',
          errors: [{ field: 'targets[2].engine', message: 'webkit is not in allowedEngines' }],
        }),
      ),
    );
    const { dialog, user } = await openDialog();
    await user.click(within(dialog).getByRole('button', { name: 'Start run' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('Engine not allowed · 422');
    expect(alert).toHaveTextContent('does not allow every requested engine');
    expect(alert).toHaveTextContent('4bf92f3577b34da6a3ce929d0e0e4736');
    expect(within(dialog).getByRole('group', { name: /Engines/ })).toHaveAccessibleDescription(
      'webkit is not in allowedEngines',
    );
    // Focus lands on the field the API named, once the form is unlocked again.
    await waitFor(() =>
      expect(within(dialog).getByRole('checkbox', { name: 'chromium' })).toHaveFocus(),
    );
  });

  it('lists API field errors that belong to no input inside the alert', async () => {
    stubApi(
      catalogApi(),
      createRunApi(() =>
        problem(409, 'idempotency-conflict', 'Idempotency conflict', {
          errors: [{ field: 'idempotencyKey', message: 'already used with a different request' }],
        }),
      ),
    );
    const { dialog, user } = await openDialog();
    await user.click(within(dialog).getByRole('button', { name: 'Start run' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'idempotencyKey: already used with a different request',
    );
  });

  it('locks the form while the request is in flight and ignores Esc until it ends', async () => {
    let finish: (response: Response) => void = () => undefined;
    stubApi(
      catalogApi(),
      createRunApi(() => new Promise<Response>((resolve) => (finish = resolve))),
    );
    const { dialog, user } = await openDialog();
    const start = within(dialog).getByRole('button', { name: 'Start run' });
    await user.click(start);

    const busy = await within(dialog).findByRole('button', { name: 'Starting run…' });
    expect(busy).toHaveAttribute('aria-busy', 'true');
    expect(busy).toHaveFocus();
    expect(within(dialog).getByLabelText('Suite')).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeDisabled();

    // jsdom does not turn Esc into a dialog `cancel` event, so the test dispatches it.
    fireEvent(dialog, new Event('cancel', { cancelable: true }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await act(async () => {
      finish(problem(503, 'internal-error', 'Service unavailable'));
      await Promise.resolve();
    });
    expect(await within(dialog).findByRole('button', { name: 'Start run' })).toBeEnabled();
  });

  it('closes on Esc and on Cancel, and gives focus back to the button that opened it', async () => {
    stubApi(catalogApi());
    const { dialog, user } = await openDialog();
    const opener = screen.getByRole('button', { name: 'New run', hidden: true });

    fireEvent(dialog, new Event('cancel', { cancelable: true }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(opener).toHaveFocus());

    await user.click(opener);
    const again = await screen.findByRole('dialog', { name: 'New run' });
    await user.click(await within(again).findByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(opener).toHaveFocus();
  });

  it('opens from a suite card with that suite chosen', async () => {
    stubApi(catalogApi());
    const { user } = renderApp(`/projects/${PROJECT_ID}/suites`);
    await user.click(await screen.findByRole('button', { name: 'Run Catalog regression' }));
    const dialog = await screen.findByRole('dialog', { name: 'New run' });
    expect(await within(dialog).findByLabelText('Suite')).toHaveValue('catalog-regression');
  });

  it('says so when the manifest has no runnable suite', async () => {
    stubApi(catalogApi({ suites: [suite({ id: 'd', suiteKey: 'off', enabled: false })] }));
    const { user } = renderApp('/');
    await user.click(await screen.findByRole('button', { name: 'New run' }));
    const dialog = await screen.findByRole('dialog', { name: 'New run' });
    expect(await within(dialog).findByText(/No suite can run/)).toBeVisible();
    expect(within(dialog).queryByRole('button', { name: 'Start run' })).toBeNull();
  });

  it('shows a load failure inside the dialog', async () => {
    stubApi(
      ({ path }) => (path === '/capabilities' ? problem(404, 'not-found', 'Not found') : undefined),
      catalogApi(),
    );
    const { user } = renderApp('/');
    await user.click(await screen.findByRole('button', { name: 'New run' }));
    const dialog = await screen.findByRole('dialog', { name: 'New run' });
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Not found');
  });

  it('has no serious or critical axe violations, open and with errors showing', async () => {
    stubApi(catalogApi());
    const { dialog, user } = await openDialog();
    await expectNoSeriousA11yViolations(dialog);

    await user.click(within(dialog).getByRole('checkbox', { name: 'chromium' }));
    await user.click(within(dialog).getByRole('checkbox', { name: 'firefox' }));
    await user.click(within(dialog).getByRole('checkbox', { name: 'webkit' }));
    await user.type(within(dialog).getByLabelText('Seed'), 'x');
    await user.click(within(dialog).getByRole('button', { name: 'Start run' }));
    await within(dialog).findByRole('alert');
    await expectNoSeriousA11yViolations(dialog);
  });
});

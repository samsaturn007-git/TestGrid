import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RunQualityView, RunResponse } from '../api/types.ts';
import { catalogApi, json, PROJECT_ID, stubApi, type StubHandler } from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { FakeEventSource } from '../test/eventSource.ts';
import { renderApp } from '../test/render.tsx';
import { quality } from '../test/qualityFixtures.ts';
import { results, run, target, timelineFor } from '../test/runFixtures.ts';

// PRD-08 FR-7 quality panel on the run page, against a fake API (mocked, not live).

afterEach(() => {
  vi.unstubAllGlobals();
});

function api(r: RunResponse, q: RunQualityView | (() => RunQualityView)): StubHandler {
  const res = results(r, []);
  return ({ path }) => {
    if (path === `/runs/${r.id}`) return json(r);
    if (path === `/runs/${r.id}/results`) return json(res);
    if (path === `/runs/${r.id}/timeline`) return json(timelineFor(r, res));
    if (path === `/runs/${r.id}/quality`) return json(typeof q === 'function' ? q() : q);
    return undefined;
  };
}

const failed = run({ status: 'FAILED', targets: [target('chromium', 'FAILED')] });

async function panel() {
  return within(await screen.findByRole('region', { name: 'Quality gate' }));
}

describe('quality panel (PRD-08 FR-7, T6)', () => {
  it('ci002 shows FAIL, the policy version and the rule table', async () => {
    stubApi(api(failed, quality(failed.id, 'FAIL')), catalogApi());
    renderApp(`/runs/${failed.id}`);
    const p = await panel();
    expect(await p.findByRole('img', { name: 'Quality gate: FAIL' })).toBeVisible();
    expect(p.getByText('2 blocking rules failed')).toBeVisible();
    expect(p.getByRole('link', { name: 'policy v3' })).toHaveAttribute(
      'href',
      `/projects/${PROJECT_ID}/policy?version=3`,
    );
    expect(p.getByText(/CLI exit 1/)).toBeVisible();
    const table = p.getByRole('table', { name: 'Quality rules' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(
      rows.map((row) => within(row).getByRole('rowheader').firstElementChild?.textContent),
    ).toEqual([
      'required-targets',
      'infra-error',
      'critical-cases',
      'max-failed-cases',
      'max-flaky-rate',
      'duration-regression',
      'quarantine-honored',
    ]);
    const critical = rows[2] as HTMLElement;
    expect(within(critical).getByRole('img', { name: 'Rule outcome: FAIL' })).toBeVisible();
    expect(critical).toHaveTextContent(/1\s*0\s*FAIL\s*yes$/);
    expect(within(critical).getByText('cart-total · chromium')).toBeInTheDocument();
    await expectNoSeriousA11yViolations();
  });

  it('ci003 explains an INDETERMINATE gate as missing evidence, not a test failure', async () => {
    const infra = run({ status: 'INFRA_ERROR', targets: [target('firefox', 'INFRA_ERROR')] });
    stubApi(api(infra, quality(infra.id, 'INDETERMINATE')), catalogApi());
    renderApp(`/runs/${infra.id}`);
    const p = await panel();
    expect(await p.findByRole('img', { name: 'Quality gate: INDETERMINATE' })).toBeVisible();
    expect(p.getByText("Couldn't decide: 2 blocking rules inconclusive")).toBeVisible();
    const why = p.getByText(
      /CI should retry the run, not treat it as a test failure/,
    ).parentElement;
    expect(why).toHaveTextContent(
      'local:firefox:0 ended INFRA_ERROR (BROWSER_LAUNCH) without test results',
    );
    expect(p.getByText(/CLI exit 2/)).toBeVisible();
  });

  it('qua001 reports a quarantined failure without counting it', async () => {
    const passed = run({ status: 'FAILED', targets: [target('chromium', 'FAILED')] });
    stubApi(api(passed, quality(passed.id, 'PASS', { quarantined: true })), catalogApi());
    renderApp(`/runs/${passed.id}`);
    const p = await panel();
    expect(await p.findByRole('img', { name: 'Quality gate: PASS' })).toBeVisible();
    expect(p.getByText(/1 quarantined failure/)).toHaveTextContent(
      '1 quarantined failure reported but not counted. See quarantines',
    );
    expect(p.getByRole('link', { name: 'See quarantines' })).toHaveAttribute(
      'href',
      '/quarantines',
    );
    expect(p.getByText('QUARANTINED_FAIL')).toBeInTheDocument();
  });

  it('asks nothing while the run is unfinished', async () => {
    const running = run({
      status: 'RUNNING',
      finishedAt: null,
      targets: [target('chromium', 'RUNNING')],
    });
    const stub = stubApi(api(running, quality(running.id, null)), catalogApi());
    vi.stubGlobal('EventSource', FakeEventSource);
    renderApp(`/runs/${running.id}`);
    const p = await panel();
    expect(p.getByText(/evaluated once the run finishes/)).toBeVisible();
    expect(stub.requests.some((r) => r.path.endsWith('/quality'))).toBe(false);
  });

  it('polls a finished run until its evaluation exists', async () => {
    let calls = 0;
    stubApi(
      api(failed, () => (++calls < 2 ? quality(failed.id, null) : quality(failed.id, 'FAIL'))),
      catalogApi(),
    );
    renderApp(`/runs/${failed.id}`);
    const p = await panel();
    expect(await p.findByText('Quality gate: evaluating…')).toBeVisible();
    await waitFor(() => expect(p.getByRole('img', { name: 'Quality gate: FAIL' })).toBeVisible(), {
      timeout: 4000,
    });
  });
});

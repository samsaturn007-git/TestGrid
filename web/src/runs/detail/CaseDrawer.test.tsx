import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RunResponse, RunResults, RunTimeline } from '../../api/types.ts';
import { catalogApi, json, problem, stubApi, type StubHandler } from '../../test/api.ts';
import { expectNoSeriousA11yViolations } from '../../test/axe.ts';
import { renderApp } from '../../test/render.tsx';
import {
  artifact,
  attempt,
  attemptResults,
  result,
  results,
  run,
  target,
  timelineFor,
} from '../../test/runFixtures.ts';

// PRD-06 T5 component tests against a fake API (mocked, not live): the case drawer (FR-8) and
// the attempt timeline (FR-7, AC-5).

afterEach(() => {
  vi.unstubAllGlobals();
});

const failedRun = run({ status: 'FAILED', targets: [target('chromium', 'FAILED')] });
const attemptOne = attempt(1, 'FAILED', { errorClass: 'ASSERTION' });
const shot = artifact(attemptOne.id, 'SCREENSHOT', 'cart-total');
const trace = artifact(attemptOne.id, 'TRACE', 'cart-total');
const log = artifact(attemptOne.id, 'LOG', null);
const withArtifacts = results(failedRun, [
  [
    attemptResults(
      attemptOne,
      [
        result('cart-total', 'FAILED', 5_000, {
          errorMessage:
            'expect(locator).toHaveText(expected) failed\nExpected: "$80.99"\nReceived: "$82.99"',
          retryCount: 1,
        }),
        result('add-to-cart', 'PASSED', 400),
      ],
      null,
      [shot, trace, log],
    ),
  ],
]);

function api(res: RunResults, timeline: RunTimeline = timelineFor(failedRun, res)): StubHandler {
  return (request) => {
    if (request.path === `/runs/${failedRun.id}`) return json(failedRun satisfies RunResponse);
    if (request.path === `/runs/${failedRun.id}/results`) return json(res);
    if (request.path === `/runs/${failedRun.id}/timeline`) return json(timeline);
    const link = /^\/artifacts\/([0-9a-f-]+)\/links$/.exec(request.path);
    if (request.method === 'POST' && link) {
      return json({
        url: `/api/v1/artifacts/${link[1]}/content?expires=1795445291&sig=${'s'.repeat(43)}`,
        expiresAt: '2026-11-22T12:05:00Z',
      });
    }
    if (request.path.startsWith(`/artifacts/${log.id}/content`)) {
      return new Response('Running 6 tests using 3 workers\n[canary] [REDACTED]\n  1 failed\n', {
        status: 200,
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      });
    }
    return undefined;
  };
}

async function openCartTotal(res: RunResults = withArtifacts) {
  const { posts } = stubApi(catalogApi(), api(res));
  const view = renderApp(`/runs/${failedRun.id}`);
  const cell = await screen.findByRole('button', {
    name: /^cart-total on chromium:.*FAILED.*Open details$/,
  });
  await view.user.click(cell);
  const dialog = await screen.findByRole('dialog', { name: /cart-total/ });
  return { ...view, cell, dialog, posts };
}

describe('case drawer (PRD-06 FR-8)', () => {
  it('shows the redacted error, the screenshot through a signed link, the trace command and the log tail', async () => {
    const { dialog, posts } = await openCartTotal();

    expect(within(dialog).getByText(/Received: "\$82\.99"/)).toBeInTheDocument();
    expect(within(dialog).getByText('ASSERTION')).toBeInTheDocument();
    expect(within(dialog).getByText('1 of 3')).toBeInTheDocument();
    const img = await within(dialog).findByRole('img', {
      name: 'Screenshot of cart-total on chromium when it failed',
    });
    expect(img).toHaveAttribute(
      'src',
      expect.stringMatching(new RegExp(`^/api/v1/artifacts/${shot.id}/content\\?expires=`)),
    );
    expect(
      within(dialog).getByText(`npx playwright show-trace trace-${trace.id}.zip`),
    ).toBeInTheDocument();
    expect(
      within(dialog).getByRole('button', { name: /Download trace \(182 KB\)/ }),
    ).toBeInTheDocument();
    const tail = await within(dialog).findByLabelText('Log tail, redacted');
    expect(tail).toHaveTextContent('[canary] [REDACTED]');
    // Links were asked for the screenshot and the log; the trace link waits for the click.
    expect(
      posts()
        .map((p) => p.path)
        .sort(),
    ).toEqual([`/artifacts/${log.id}/links`, `/artifacts/${shot.id}/links`].sort());
    expect(within(dialog).getAllByText('AVAILABLE')).toHaveLength(3);
    await expectNoSeriousA11yViolations();
  });

  it('art002: explains expired and missing files instead of showing a broken image', async () => {
    const expired = results(failedRun, [
      [
        attemptResults(attemptOne, [result('cart-total', 'FAILED', 5_000)], null, [
          { ...shot, state: 'EXPIRED' },
          { ...trace, state: 'MISSING' },
        ]),
      ],
    ]);
    const { dialog, posts } = await openCartTotal(expired);

    expect(within(dialog).queryByRole('img', { name: /^Screenshot of/ })).not.toBeInTheDocument();
    expect(
      within(dialog).getByText(/This screenshot was deleted after its retention period/),
    ).toBeInTheDocument();
    expect(within(dialog).getByText(/The trace file is missing from storage/)).toBeInTheDocument();
    expect(within(dialog).getByText('No log tail was kept for this attempt.')).toBeInTheDocument();
    expect(within(dialog).getByText('EXPIRED')).toBeInTheDocument();
    expect(within(dialog).getByText('MISSING')).toBeInTheDocument();
    expect(posts()).toEqual([]);
    await expectNoSeriousA11yViolations();
  });

  it('focus moves into the drawer, stays inside on Tab, and Esc returns it to the cell', async () => {
    const { dialog, cell, user } = await openCartTotal();

    expect(within(dialog).getByRole('button', { name: /close case details$/ })).toHaveFocus();
    await user.tab({ shift: true });
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(cell).toHaveFocus();
  });

  it('a link that cannot be issued (410) is reported, not shown as a broken image', async () => {
    stubApi(
      catalogApi(),
      (request) =>
        request.method === 'POST'
          ? problem(410, 'artifact-expired', 'Artifact expired')
          : undefined,
      api(withArtifacts),
    );
    const view = renderApp(`/runs/${failedRun.id}`);
    await view.user.click(
      await screen.findByRole('button', {
        name: /^cart-total on chromium:.*FAILED.*Open details$/,
      }),
    );
    const dialog = await screen.findByRole('dialog');
    expect(
      await within(dialog).findByText('Could not get a link to the screenshot.'),
    ).toBeInTheDocument();
    expect(within(dialog).queryByRole('img', { name: /^Screenshot of/ })).not.toBeInTheDocument();
  });
});

describe('attempt timeline (PRD-06 FR-7, AC-5)', () => {
  it('shows the abandoned attempt above its retry, with its heartbeat gap in words', async () => {
    const retried = run({ status: 'FAILED', targets: [target('chromium', 'FAILED')] });
    const abandoned = attempt(1, 'ABANDONED', {
      errorClass: 'LEASE_EXPIRED',
      claimedAt: '2026-11-22T11:50:00.000Z',
      startedAt: '2026-11-22T11:50:00.400Z',
      finishedAt: '2026-11-22T11:50:45.000Z',
    });
    const second = attempt(2, 'FAILED', {
      errorClass: 'ASSERTION',
      claimedAt: '2026-11-22T11:50:50.000Z',
      startedAt: '2026-11-22T11:50:50.300Z',
      finishedAt: '2026-11-22T11:51:10.000Z',
    });
    const res = results(retried, [
      [
        attemptResults(second, [result('cart-total', 'FAILED', 5_000, { startOffsetMs: 2_000 })]),
        attemptResults(abandoned),
      ],
    ]);
    const timeline = timelineFor(retried, res);
    timeline.targets[0]!.attempts[0]!.heartbeats = ['2026-11-22T11:50:10.400Z'];
    timeline.targets[0]!.attempts[0]!.gaps = [
      { from: '2026-11-22T11:50:10.400Z', to: '2026-11-22T11:50:45.000Z', durationMs: 34_600 },
    ];
    stubApi(catalogApi(), (request) => {
      if (request.path === `/runs/${retried.id}`) return json(retried);
      if (request.path === `/runs/${retried.id}/results`) return json(res);
      if (request.path === `/runs/${retried.id}/timeline`) return json(timeline);
      return undefined;
    });
    renderApp(`/runs/${retried.id}`);

    const section = await screen.findByRole('region', { name: 'Timeline' });
    const tracks = await within(section).findByRole('list', {
      name: 'Attempts of local:chromium:0, oldest first',
    });
    const items = within(tracks).getAllByRole('listitem');
    expect(items.map((li) => within(li).getByText(/^attempt \d$/).textContent)).toEqual([
      'attempt 1',
      'attempt 2',
    ]);
    expect(within(items[0]!).getByText('LEASE_EXPIRED')).toBeInTheDocument();
    expect(
      within(items[0]!).getByText(/no heartbeat for 34\.6 s \(11:50:10 to 11:50:45\)/),
    ).toBeInTheDocument();
    expect(within(items[1]!).queryByText(/no heartbeat for/)).not.toBeInTheDocument();
    expect(within(section).getByText(/a silence over 20\.0 s is marked/)).toBeInTheDocument();
    await waitFor(() => expectNoSeriousA11yViolations());
  });
});

import { act, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RunResponse } from '../../api/types.ts';
import { catalogApi, json, stubApi } from '../../test/api.ts';
import { expectNoSeriousA11yViolations } from '../../test/axe.ts';
import { FakeEventSource } from '../../test/eventSource.ts';
import { renderApp } from '../../test/render.tsx';
import { attempt, attemptResults, results, run, target } from '../../test/runFixtures.ts';
import {
  COALESCE_MS,
  MAX_FAILED_RECONNECTS,
  POLL_MS,
  RECONNECT_TIMEOUT_MS,
  RETRY_STREAM_MS,
} from '../useRunEvents.ts';

// PRD-05 FR-3 against a fake API and a fake EventSource (mocked, not live). The browser's own
// reconnect and a really blocked stream are covered by the Playwright E2E (AC-3, T7).

const RUN_ID = '0e6651a1-0000-4000-8000-0000000000aa';
const running = run({
  id: RUN_ID,
  status: 'RUNNING',
  finishedAt: null,
  targets: [target('chromium', 'RUNNING'), target('firefox', 'QUEUED')],
});
const finished = run({
  ...running,
  status: 'FAILED',
  finishedAt: '2026-11-22T11:53:00.000Z',
  targets: [target('chromium', 'FAILED'), target('firefox', 'PASSED')],
});

/** A fake API whose run the test can change, counting snapshot fetches. */
function liveApi(initial: RunResponse) {
  const state = { run: initial, runFetches: 0 };
  const api = stubApi(({ method, path }) => {
    if (method !== 'GET') return undefined;
    if (path === `/runs/${RUN_ID}`) {
      state.runFetches += 1;
      return json(state.run);
    }
    if (path === `/runs/${RUN_ID}/results`) {
      return json(
        results(
          state.run,
          state.run.targets.map(() => [attemptResults(attempt(1, 'RUNNING'))]),
        ),
      );
    }
    return undefined;
  }, catalogApi());
  return { state, api };
}

async function openRunning(initial: RunResponse = running) {
  const live = liveApi(initial);
  const app = renderApp(`/runs/${RUN_ID}`);
  await screen.findByText('Connecting to live updates…');
  const es = FakeEventSource.latest();
  act(() => es.open());
  await screen.findByText('Live');
  return { ...live, ...app, es };
}

const headerBadge = () =>
  within(screen.getByRole('region', { name: 'Checkout smoke' })).getAllByRole('img')[0];

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('live run updates (PRD-05 FR-3, T5)', () => {
  it('opens one stream for an unfinished run, at the run’s events URL', async () => {
    const { es } = await openRunning();
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(es.url).toBe(`/api/v1/runs/${RUN_ID}/events`);
  });

  it('opens no stream for a finished run', async () => {
    liveApi(finished);
    renderApp(`/runs/${RUN_ID}`);
    await screen.findByRole('button', { name: 'Rerun all' });
    expect(FakeEventSource.instances).toHaveLength(0);
    expect(screen.queryByRole('status', { name: /Live/ })).toBeNull();
  });

  it('refetches the snapshot on an event and shows the server’s new state', async () => {
    const { state, es } = await openRunning();
    expect(headerBadge()).toHaveAccessibleName('Run status: RUNNING');

    state.run = finished;
    act(() => es.emit('RUN_FINISHED', 41, { status: 'FAILED' }));
    await waitFor(() => expect(headerBadge()).toHaveAccessibleName('Run status: FAILED'));
    // The page never takes the status from the event itself, only from the snapshot.
    expect(screen.getByRole('button', { name: 'Rerun all' })).toBeVisible();
  });

  it('serves a burst of events with one refetch', async () => {
    const { state, es } = await openRunning();
    const before = state.runFetches;
    act(() => {
      es.emit('TARGET_FINISHED', 10);
      es.emit('CASE_FINISHED', 11);
      es.emit('CASE_FINISHED', 12);
    });
    await waitFor(() => expect(state.runFetches).toBe(before + 1));
    await new Promise((r) => setTimeout(r, COALESCE_MS * 3));
    expect(state.runFetches).toBe(before + 1);
  });

  it('ignores a repeated or older event id', async () => {
    const { state, es } = await openRunning();
    act(() => es.emit('TARGET_STARTED', 20));
    await waitFor(() => expect(state.runFetches).toBe(2));
    act(() => {
      es.emit('TARGET_STARTED', 20);
      es.emit('TARGET_CLAIMED', 19);
    });
    await new Promise((r) => setTimeout(r, COALESCE_MS * 3));
    expect(state.runFetches).toBe(2);
  });

  it('refetches at once on RESYNC, and on a frame it cannot read', async () => {
    const { state, es } = await openRunning();
    act(() => es.emit('TARGET_STARTED', 50));
    await waitFor(() => expect(state.runFetches).toBe(2));

    act(() => es.emit('RESYNC', 5, { runId: RUN_ID, eventId: 5 }));
    await waitFor(() => expect(state.runFetches).toBe(3));

    act(() => es.emit('TARGET_FINISHED', 60, 'not json {'));
    await waitFor(() => expect(state.runFetches).toBe(4));
  });

  it('says it is reconnecting after a drop, and is live again when the stream comes back', async () => {
    const { es } = await openRunning();
    act(() => es.fail());
    expect(await screen.findByText('Connecting to live updates…')).toBeVisible();
    // EventSource reconnects by itself (with Last-Event-ID); no new stream is made.
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(headerBadge()?.querySelector('svg')).not.toHaveClass('tg-pulse');

    act(() => es.open());
    expect(await screen.findByText('Live')).toBeVisible();
    expect(headerBadge()?.querySelector('svg')).toHaveClass('tg-pulse');
  });

  it('after two failed reconnects, polls every 5 s with a banner, then retries the stream', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { state, es } = await openRunning();

    act(() => {
      for (let i = 0; i <= MAX_FAILED_RECONNECTS; i++) es.fail(); // the drop, then 2 retries
    });
    const banner = await screen.findByText('Live updates paused, refreshing every 5s');
    expect(banner).toHaveAttribute('role', 'status');
    expect(es.closed).toBe(true);

    const before = state.runFetches;
    await act(() => vi.advanceTimersByTimeAsync(POLL_MS + 100));
    expect(state.runFetches).toBeGreaterThan(before);

    // A fresh stream is tried later; once it opens, polling stops and the banner goes.
    await act(() => vi.advanceTimersByTimeAsync(RETRY_STREAM_MS));
    expect(FakeEventSource.instances).toHaveLength(2);
    act(() => FakeEventSource.latest().open());
    expect(await screen.findByText('Live')).toBeVisible();
    expect(screen.queryByText('Live updates paused, refreshing every 5s')).toBeNull();
    const settled = state.runFetches;
    await act(() => vi.advanceTimersByTimeAsync(POLL_MS * 3));
    expect(state.runFetches).toBe(settled);
  });

  it('falls back when a reconnect hangs instead of failing (a proxy holding it)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { es } = await openRunning();
    act(() => es.fail()); // dropped; the reconnect then neither opens nor errors
    await screen.findByText('Connecting to live updates…');
    await act(() => vi.advanceTimersByTimeAsync(RECONNECT_TIMEOUT_MS - 1_000));
    expect(screen.queryByText('Live updates paused, refreshing every 5s')).toBeNull();
    await act(() => vi.advanceTimersByTimeAsync(1_500));
    expect(await screen.findByText('Live updates paused, refreshing every 5s')).toBeVisible();
    expect(es.closed).toBe(true);
  });

  it('keeps the stream when it comes back before the reconnect timeout', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { es } = await openRunning();
    act(() => es.fail());
    await act(() => vi.advanceTimersByTimeAsync(RECONNECT_TIMEOUT_MS / 2));
    act(() => es.open());
    await screen.findByText('Live');
    await act(() => vi.advanceTimersByTimeAsync(RECONNECT_TIMEOUT_MS));
    expect(screen.getByText('Live')).toBeVisible();
    expect(es.closed).toBe(false);
  });

  it('falls back at once when the stream is refused outright', async () => {
    liveApi(running);
    renderApp(`/runs/${RUN_ID}`);
    await screen.findByText('Connecting to live updates…');
    act(() => FakeEventSource.latest().fail(true));
    expect(await screen.findByText('Live updates paused, refreshing every 5s')).toBeVisible();
  });

  it('settles on the database state after losing the stream mid-run, never a stale RUNNING (AC-3)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { state, es } = await openRunning();
    act(() => {
      for (let i = 0; i <= MAX_FAILED_RECONNECTS; i++) es.fail();
    });
    await screen.findByText('Live updates paused, refreshing every 5s');

    // The run finishes while the page has no stream.
    state.run = finished;
    await act(() => vi.advanceTimersByTimeAsync(POLL_MS + 100));
    await waitFor(() => expect(headerBadge()).toHaveAccessibleName('Run status: FAILED'));
    expect(document.querySelectorAll('.tg-pulse')).toHaveLength(0);

    // Finished: no more polling and no new stream.
    const settled = state.runFetches;
    await act(() => vi.advanceTimersByTimeAsync(RETRY_STREAM_MS + POLL_MS));
    expect(state.runFetches).toBe(settled);
    expect(FakeEventSource.instances).toHaveLength(1);
  });

  it('closes the stream once the run has finished', async () => {
    const { state, es } = await openRunning();
    state.run = finished;
    act(() => es.emit('RUN_FINISHED', 99));
    await screen.findByRole('button', { name: 'Rerun all' });
    expect(es.closed).toBe(true);
  });

  it('has no serious or critical axe violations with the fallback banner showing', async () => {
    const { es } = await openRunning();
    act(() => es.fail(true));
    await screen.findByText('Live updates paused, refreshing every 5s');
    await expectNoSeriousA11yViolations();
  });
});

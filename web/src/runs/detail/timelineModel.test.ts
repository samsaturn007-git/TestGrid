import { describe, expect, it } from 'vitest';
import type { TimelineAttempt, TimelineTarget } from '../../api/types.ts';
import { artifact, attempt, result } from '../../test/runFixtures.ts';
import { layoutTarget } from './timelineModel.ts';

// PRD-06 FR-7: positions are percentages of one target's span; nothing is placed at a guess.

const at = (s: number) => new Date(Date.UTC(2026, 10, 22, 12, 0, 0) + s * 1000).toISOString();

function timelineAttempt(overrides: Partial<TimelineAttempt>): TimelineAttempt {
  return {
    attempt: attempt(1, 'FAILED', { claimedAt: at(0), startedAt: at(0), finishedAt: at(100) }),
    heartbeats: [],
    gaps: [],
    caseResults: [],
    artifacts: [],
    events: [],
    ...overrides,
  };
}

function target(attempts: TimelineAttempt[]): TimelineTarget {
  return {
    id: 't',
    targetKey: 'local:chromium:0',
    engine: 'chromium',
    status: 'FAILED',
    events: [],
    attempts,
  };
}

describe('timeline layout', () => {
  it('places case bars from their start offsets and leaves untimed cases unplaced', () => {
    const layout = layoutTarget(
      target([
        timelineAttempt({
          caseResults: [
            result('cart-total', 'FAILED', 10_000, { startOffsetMs: 20_000 }),
            result('add-to-cart', 'PASSED', 5_000, { startOffsetMs: 0 }),
            result('browse-catalog', 'PASSED', 1_000, { startOffsetMs: null }),
          ],
          heartbeats: [at(10), at(50)],
          artifacts: [artifact('a', 'SCREENSHOT', 'cart-total', { createdAt: at(90) })],
        }),
      ]),
      at(100),
    );
    const track = layout.tracks[0]!;
    expect(track.span).toEqual({ left: 0, width: 100 });
    expect(track.cases.map((c) => [c.result.caseKey, c.left, c.width, c.lane])).toEqual([
      ['add-to-cart', 0, 5, 0],
      ['cart-total', 20, 10, 0],
    ]);
    expect(track.lanes).toBe(1);
    expect(track.untimed.map((c) => c.caseKey)).toEqual(['browse-catalog']);
    expect(track.beats).toEqual([10, 50]);
    expect(track.artifacts.map((a) => a.at)).toEqual([90]);
    expect(layout.ticks.map((t) => t.label)).toEqual([
      '12:00:00',
      '12:00:25',
      '12:00:50',
      '12:01:15',
      '12:01:40',
    ]);
  });

  it('shares one scale across attempts, so an abandoned attempt sits before its retry', () => {
    const abandoned = timelineAttempt({
      attempt: attempt(1, 'ABANDONED', { claimedAt: at(0), startedAt: at(1), finishedAt: at(45) }),
      heartbeats: [at(11)],
      gaps: [{ from: at(11), to: at(45), durationMs: 34_000 }],
    });
    const retry = timelineAttempt({
      attempt: attempt(2, 'PASSED', { claimedAt: at(50), startedAt: at(50), finishedAt: at(100) }),
    });
    const layout = layoutTarget(target([abandoned, retry]), at(100));
    expect(layout.tracks.map((t) => t.span)).toEqual([
      { left: 0, width: 45 },
      { left: 50, width: 50 },
    ]);
    expect(layout.tracks[0]!.gaps.map((g) => [g.left, g.width, g.gap.durationMs])).toEqual([
      [11, 34, 34_000],
    ]);
  });

  it('an unfinished attempt runs to the server clock, and a target with no attempt has no tracks', () => {
    const running = timelineAttempt({
      attempt: attempt(1, 'RUNNING', { claimedAt: at(0), startedAt: at(0), finishedAt: null }),
    });
    expect(layoutTarget(target([running]), at(30)).endMs - Date.parse(at(0))).toBe(30_000);
    expect(layoutTarget(target([]), at(30)).tracks).toEqual([]);
  });

  it('never draws a bar too thin to see', () => {
    const layout = layoutTarget(
      target([timelineAttempt({ caseResults: [result('a', 'PASSED', 1, { startOffsetMs: 0 })] })]),
      at(100),
    );
    expect(layout.tracks[0]!.cases[0]!.width).toBeGreaterThanOrEqual(0.6);
  });

  it('stacks cases that ran at the same time into lanes, so none hides another', () => {
    const layout = layoutTarget(
      target([
        timelineAttempt({
          caseResults: [
            result('long-a', 'TIMED_OUT', 40_000, { startOffsetMs: 1_000 }),
            result('short', 'PASSED', 1_000, { startOffsetMs: 0 }),
            result('long-b', 'TIMED_OUT', 40_000, { startOffsetMs: 1_500 }),
            result('after-short', 'PASSED', 500, { startOffsetMs: 1_200 }),
          ],
        }),
      ]),
      at(100),
    );
    const lanes = Object.fromEntries(
      layout.tracks[0]!.cases.map((c) => [c.result.caseKey, c.lane]),
    );
    // short ends as long-a starts, so long-a reuses lane 0.
    expect(lanes).toEqual({ short: 0, 'long-a': 0, 'after-short': 1, 'long-b': 2 });
    expect(layout.tracks[0]!.lanes).toBe(3);
  });
});

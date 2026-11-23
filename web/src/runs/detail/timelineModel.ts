import type {
  ArtifactView,
  CaseResult,
  HeartbeatGap,
  TimelineAttempt,
  TimelineTarget,
} from '../../api/types.ts';
import { formatClock } from '../../lib/time.ts';

// Pure layout for the attempt timeline (PRD-06 FR-7). Every position is a percentage of one
// target's span, from its first claim to its last end, so tracks of one target line up.

export interface Span {
  /** Percent of the target's span, 0..100. */
  left: number;
  width: number;
}

export interface CaseBar extends Span {
  result: CaseResult;
  /** Row inside the track: cases that ran at the same time get different lanes. */
  lane: number;
}

export interface GapBand extends Span {
  gap: HeartbeatGap;
}

export interface Track {
  attempt: TimelineAttempt;
  span: Span;
  cases: CaseBar[];
  /** How many lanes the case bars need (1 when cases ran one after another). */
  lanes: number;
  /** Cases the worker gave no start offset for: listed, never placed at a guessed time. */
  untimed: CaseResult[];
  beats: number[];
  gaps: GapBand[];
  artifacts: { at: number; artifact: ArtifactView }[];
}

export interface TargetLayout {
  startMs: number;
  endMs: number;
  ticks: { at: number; label: string }[];
  tracks: Track[];
}

/** Bars thinner than this would vanish; they are widened, never moved. */
const MIN_WIDTH = 0.6;

const ms = (iso: string) => Date.parse(iso);

function endOf(attempt: TimelineAttempt, serverNow: string): number {
  return ms(attempt.attempt.finishedAt ?? serverNow);
}

/**
 * Playwright runs cases in parallel, so bars overlap. Greedy interval packing: by start time,
 * each bar takes the first lane that is free by then. O(c log c + c·l) for c cases in l lanes;
 * l is at most Playwright's worker count.
 */
function packLanes(bars: (Span & { result: CaseResult; from: number; to: number })[]): {
  cases: CaseBar[];
  lanes: number;
} {
  const laneEnds: number[] = [];
  const cases = [...bars]
    .sort((a, b) => a.from - b.from || a.result.caseKey.localeCompare(b.result.caseKey))
    .map(({ result, left, width, from, to }) => {
      let lane = laneEnds.findIndex((end) => end <= from);
      if (lane === -1) lane = laneEnds.push(to) - 1;
      else laneEnds[lane] = to;
      return { result, left, width, lane };
    });
  return { cases, lanes: Math.max(1, laneEnds.length) };
}

export function layoutTarget(
  target: TimelineTarget,
  serverNow: string,
  tickCount = 5,
): TargetLayout {
  const attempts = target.attempts;
  if (attempts.length === 0) return { startMs: 0, endMs: 0, ticks: [], tracks: [] };
  const startMs = Math.min(...attempts.map((a) => ms(a.attempt.claimedAt)));
  const endMs = Math.max(
    startMs + 1,
    ...attempts.map((a) => endOf(a, serverNow)),
    ...attempts.flatMap((a) => a.artifacts.map((x) => ms(x.createdAt))),
  );
  const pct = (t: number) => Math.min(100, Math.max(0, ((t - startMs) / (endMs - startMs)) * 100));
  const span = (from: number, to: number): Span => {
    const left = pct(from);
    return { left, width: Math.max(MIN_WIDTH, pct(to) - left) };
  };

  const tracks = attempts.map((attempt): Track => {
    const s = attempt.attempt;
    // Offsets count from the attempt's first case, which is aligned to the attempt's start.
    const anchor = ms(s.startedAt ?? s.claimedAt);
    const timed = attempt.caseResults.filter((c) => c.startOffsetMs !== null);
    const { cases, lanes } = packLanes(
      timed.map((result) => {
        const from = anchor + (result.startOffsetMs ?? 0);
        return {
          result,
          from,
          to: from + result.durationMs,
          ...span(from, from + result.durationMs),
        };
      }),
    );
    return {
      attempt,
      span: span(ms(s.claimedAt), endOf(attempt, serverNow)),
      cases,
      lanes,
      untimed: attempt.caseResults.filter((c) => c.startOffsetMs === null),
      beats: attempt.heartbeats.map((b) => pct(ms(b))),
      gaps: attempt.gaps.map((gap) => ({ gap, ...span(ms(gap.from), ms(gap.to)) })),
      artifacts: attempt.artifacts.map((artifact) => ({
        at: pct(ms(artifact.createdAt)),
        artifact,
      })),
    };
  });

  const ticks = Array.from({ length: tickCount }, (_, i) => {
    const t = startMs + ((endMs - startMs) * i) / (tickCount - 1);
    return {
      at: (i * 100) / (tickCount - 1),
      label: formatClock(new Date(t).toISOString()).slice(0, 8),
    };
  });
  return { startMs, endMs, ticks, tracks };
}

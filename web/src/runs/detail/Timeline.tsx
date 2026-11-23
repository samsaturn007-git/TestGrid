import type { CaseStatus, RunTimeline, TimelineTarget } from '../../api/types.ts';
import { EngineMark } from '../../components/Engine.tsx';
import { ErrorState, LoadingState } from '../../components/States.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { formatClock, formatDuration } from '../../lib/time.ts';
import { layoutTarget, type Track } from './timelineModel.ts';

// PRD-06 FR-7: one block per target, one track per attempt (oldest first, so an ABANDONED attempt
// stays above its retry). The drawing is decorative (aria-hidden); every fact in it is also in
// the text line under each track, so nothing is only visible as a shape or colour.

const CASE_BAR: Record<CaseStatus, string> = {
  PASSED: 'bg-success',
  FAILED: 'bg-danger',
  TIMED_OUT: 'bg-warning',
  FLAKY: 'bg-flaky',
  SKIPPED: 'border border-dashed border-text-muted bg-transparent',
};

const HATCH = {
  backgroundImage:
    'repeating-linear-gradient(45deg, color-mix(in srgb, var(--color-abandoned) 32%, transparent) 0 4px, transparent 4px 8px)',
};

// Case bars share the band between the span line and the heartbeat ticks (px 18..46).
const BAND_TOP = 18;
const BAND_HEIGHT = 28;
function laneBox(lane: number, lanes: number) {
  const height = BAND_HEIGHT / lanes;
  return { top: `${BAND_TOP + lane * height}px`, height: `${Math.max(3, height - 1)}px` };
}

const pos = (left: number, width?: number) => ({
  left: `${left}%`,
  ...(width === undefined ? {} : { width: `${width}%` }),
});

function Legend() {
  const item = 'inline-flex items-center gap-1.5';
  return (
    <ul
      className="flex flex-wrap gap-x-4 gap-y-1 text-body-sm text-text-secondary"
      aria-label="Legend"
    >
      <li className={item}>
        <span aria-hidden="true" className="h-2 w-3.5 rounded-[2px] bg-success" />
        case passed
      </li>
      <li className={item}>
        <span aria-hidden="true" className="h-2 w-3.5 rounded-[2px] bg-danger" />
        case failed
      </li>
      <li className={item}>
        <span aria-hidden="true" className="h-2 w-3.5 rounded-[2px] bg-warning" />
        case timed out
      </li>
      <li className={item}>
        <span aria-hidden="true" className="h-2 w-3.5 rounded-[2px] bg-flaky" />
        case flaky
      </li>
      <li className={item}>
        <span aria-hidden="true" className="h-3 w-0.5 bg-text-secondary" />
        heartbeat
      </li>
      <li className={item}>
        <span
          aria-hidden="true"
          className="h-2.5 w-3.5 rounded-[2px] border border-abandoned"
          style={HATCH}
        />
        no heartbeat for longer than 2 × the interval
      </li>
      <li className={item}>
        <span aria-hidden="true" className="size-2 rotate-45 bg-info" />
        artifact stored
      </li>
    </ul>
  );
}

function trackSummary(track: Track): string {
  const s = track.attempt.attempt;
  const parts = [`claimed ${formatClock(s.claimedAt).slice(0, 8)}`];
  parts.push(
    track.attempt.heartbeats.length === 1
      ? '1 heartbeat'
      : `${track.attempt.heartbeats.length} heartbeats`,
  );
  for (const { gap } of track.gaps) {
    parts.push(
      `no heartbeat for ${formatDuration(gap.durationMs)} (${formatClock(gap.from).slice(0, 8)} to ${formatClock(gap.to).slice(0, 8)})`,
    );
  }
  if (track.attempt.artifacts.length > 0) {
    parts.push(
      track.attempt.artifacts.length === 1
        ? '1 artifact'
        : `${track.attempt.artifacts.length} artifacts`,
    );
  }
  if (track.untimed.length > 0) parts.push(`${track.untimed.length} cases without timing`);
  parts.push(s.finishedAt ? `ended ${formatClock(s.finishedAt).slice(0, 8)}` : 'still running');
  return parts.join(' · ');
}

function TrackRow({ track }: { track: Track }) {
  const s = track.attempt.attempt;
  return (
    <li className="grid grid-cols-[9rem_minmax(0,1fr)] items-start gap-3">
      <div className="flex flex-col gap-1.5 pt-1">
        <span className="font-mono text-mono-sm font-semibold">attempt {s.attemptNo}</span>
        <span className="flex flex-wrap items-center gap-1">
          <StatusBadge kind="attempt" status={s.status} size="dense" animate={false} />
          {s.errorClass ? (
            <span className="font-mono text-mono-sm text-text-muted">{s.errorClass}</span>
          ) : null}
        </span>
      </div>
      <div className="flex flex-col gap-1.5">
        <div
          aria-hidden="true"
          className="relative h-16 rounded-chip border border-hairline-soft bg-canvas"
        >
          {track.gaps.map(({ gap, left, width }) => (
            <span
              key={gap.from}
              title={`no heartbeat for ${formatDuration(gap.durationMs)}`}
              className="absolute inset-y-0 border-x border-abandoned"
              style={{ ...pos(left, width), ...HATCH }}
            />
          ))}
          <span
            className="absolute top-1.5 h-1 rounded-full bg-hairline-strong"
            style={pos(track.span.left, track.span.width)}
          />
          {track.cases.map(({ result, left, width, lane }) => (
            <span
              key={result.caseKey}
              title={`${result.caseKey} ${result.status} ${formatDuration(result.durationMs)}`}
              className={`absolute rounded-[2px] ${CASE_BAR[result.status]}`}
              style={{ ...pos(left, width), ...laneBox(lane, track.lanes) }}
            />
          ))}
          {track.beats.map((at, i) => (
            <span
              key={i}
              className="absolute bottom-1 h-3 w-0.5 bg-text-secondary"
              style={pos(at)}
            />
          ))}
          {track.artifacts.map(({ at, artifact }) => (
            <span
              key={artifact.id}
              title={`${artifact.kind}${artifact.caseKey ? ` · ${artifact.caseKey}` : ''} · ${artifact.state}`}
              className="absolute top-3 -ml-1 size-2 rotate-45 bg-info"
              style={pos(at)}
            />
          ))}
        </div>
        <p
          className={`text-body-sm ${track.gaps.length > 0 ? 'text-abandoned' : 'text-text-muted'}`}
        >
          {trackSummary(track)}
        </p>
      </div>
    </li>
  );
}

function TargetBlock({ target, serverNow }: { target: TimelineTarget; serverNow: string }) {
  const layout = layoutTarget(target, serverNow);
  const headingId = `timeline-${target.id}`;
  return (
    <article
      aria-labelledby={headingId}
      className="flex flex-col gap-3 rounded-panel border border-hairline bg-surface p-4"
    >
      <h3 id={headingId} className="flex items-center gap-2 font-mono text-mono font-medium">
        <EngineMark engine={target.engine} />
        {target.targetKey}
        <StatusBadge kind="target" status={target.status} size="dense" animate={false} />
      </h3>
      {layout.tracks.length === 0 ? (
        <p className="text-body-sm text-text-muted">No attempt yet, so nothing to draw.</p>
      ) : (
        <>
          <div
            aria-hidden="true"
            className="relative ml-[calc(9rem+0.75rem)] h-4 border-b border-hairline-soft"
          >
            {layout.ticks.map((tick) => (
              <span
                key={tick.at}
                className="absolute bottom-1 -translate-x-1/2 font-mono text-[11px] text-text-muted first:translate-x-0 last:-translate-x-full"
                style={pos(tick.at)}
              >
                {tick.label}
              </span>
            ))}
          </div>
          <ol
            className="flex flex-col gap-4"
            aria-label={`Attempts of ${target.targetKey}, oldest first`}
          >
            {layout.tracks.map((track) => (
              <TrackRow key={track.attempt.attempt.id} track={track} />
            ))}
          </ol>
        </>
      )}
    </article>
  );
}

/** Times are UTC, like the rest of the run page. */
export function Timeline({
  timeline,
  loading,
  error,
}: {
  timeline: RunTimeline | undefined;
  loading: boolean;
  error: unknown;
}) {
  return (
    <section aria-labelledby="timeline-h" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="timeline-h" className="text-heading">
          Timeline
        </h2>
        {timeline ? (
          <span className="text-body-sm text-text-muted">
            Heartbeat every {timeline.heartbeatIntervalSeconds} s; a silence over{' '}
            {formatDuration(timeline.gapThresholdMs)} is marked. Times in UTC.
          </span>
        ) : null}
      </div>
      {timeline ? (
        <>
          <Legend />
          {timeline.targets.map((target) => (
            <TargetBlock key={target.id} target={target} serverNow={timeline.serverNow} />
          ))}
        </>
      ) : loading ? (
        <LoadingState label="Loading timeline…" />
      ) : error ? (
        <ErrorState error={error} />
      ) : null}
    </section>
  );
}

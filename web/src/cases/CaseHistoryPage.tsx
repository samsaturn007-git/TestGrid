import { Link, useParams, useSearchParams } from 'react-router';
import { CASE_HISTORY_LIMIT, useCaseHistory } from '../api/queries.ts';
import type {
  CaseHealth,
  CaseHealthLabel,
  CaseHistoryResult,
  CaseStatus,
  EngineHistory,
} from '../api/types.ts';
import { EngineChip } from '../components/Engine.tsx';
import { EmptyState, ErrorState, LoadingState } from '../components/States.tsx';
import { ROLE_CLASSES, type StatusRole } from '../components/status.ts';
import { formatAbsolute, formatDuration } from '../lib/time.ts';
import { Page } from '../pages/Page.tsx';

// PRD-07 FR-5: one case's recent results per engine (newest on the right, each linking to its
// run), a duration sparkline, and the health label with the reason it was given (FR-4).

const LABEL_ROLE: Record<CaseHealthLabel, StatusRole> = {
  STABLE: 'success',
  FLAKY: 'flaky',
  FAILING: 'danger',
  INSUFFICIENT_DATA: 'neutral',
};

// Literal classes so Tailwind generates them. Colour is never the only signal: every cell has a
// glyph and an aria-label with its status.
const CELL: Record<CaseStatus, { className: string; glyph: string }> = {
  PASSED: { className: 'bg-success text-canvas', glyph: '' },
  FAILED: { className: 'bg-danger text-canvas', glyph: '×' },
  TIMED_OUT: { className: 'bg-warning text-canvas', glyph: 'T' },
  FLAKY: { className: 'bg-flaky text-canvas', glyph: '~' },
  SKIPPED: { className: 'border border-dashed border-neutral text-neutral', glyph: 'S' },
};

function HealthBadge({ health }: { health: CaseHealth | null }) {
  const label = health?.label ?? 'INSUFFICIENT_DATA';
  const classes = ROLE_CLASSES[LABEL_ROLE[label]];
  return (
    <span
      data-label={label}
      className={`inline-flex h-6 items-center rounded-chip border px-2 font-mono text-mono-sm ${classes.chip} ${label === 'INSUFFICIENT_DATA' ? 'border-dashed' : ''}`}
    >
      <span className="sr-only">Health: </span>
      {label}
    </span>
  );
}

function cellLabel(r: CaseHistoryResult, engine: string): string {
  return `${r.status} on ${engine}, ${formatDuration(r.durationMs)}, ${formatAbsolute(r.finishedAt)}, run ${r.runId.slice(0, 8)}`;
}

function OutcomeStrip({ row }: { row: EngineHistory }) {
  // The API sends newest first; the strip reads left to right in time.
  const oldestFirst = [...row.results].reverse();
  const empty = Math.max(0, CASE_HISTORY_LIMIT - oldestFirst.length);
  return (
    <ol
      aria-label={`Last ${oldestFirst.length} results on ${row.engine}, oldest first`}
      className="flex flex-wrap gap-[3px]"
    >
      {Array.from({ length: empty }, (_, i) => (
        <li
          key={`empty-${i}`}
          aria-hidden="true"
          className="h-[22px] w-[18px] rounded-[3px] border border-dashed border-hairline-strong"
        />
      ))}
      {oldestFirst.map((r) => {
        const cell = CELL[r.status];
        const label = cellLabel(r, row.engine);
        return (
          <li key={`${r.attemptId}`}>
            <Link
              to={`/runs/${r.runId}`}
              aria-label={label}
              title={label}
              className={`flex h-[22px] w-[18px] items-center justify-center rounded-[3px] font-mono text-[10px] font-bold ${cell.className}`}
            >
              <span aria-hidden="true">{cell.glyph}</span>
            </Link>
          </li>
        );
      })}
    </ol>
  );
}

const SPARK_W = 150;
const SPARK_H = 28;

/** Durations of the results that ran (SKIPPED has no meaningful duration), oldest first. */
export function sparklinePoints(results: CaseHistoryResult[]): string {
  const durations = [...results]
    .reverse()
    .filter((r) => r.status !== 'SKIPPED')
    .map((r) => r.durationMs);
  if (durations.length < 2) return '';
  const min = Math.min(...durations);
  const span = Math.max(...durations) - min || 1;
  return durations
    .map((d, i) => {
      const x = 2 + (i * (SPARK_W - 4)) / (durations.length - 1);
      const y = SPARK_H - 2 - ((d - min) / span) * (SPARK_H - 4);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}

function Sparkline({ row }: { row: EngineHistory }) {
  const ran = row.results.filter((r) => r.status !== 'SKIPPED');
  if (ran.length < 2) {
    return <span className="text-body-sm text-text-muted">Not enough results for a trend.</span>;
  }
  const durations = ran.map((r) => r.durationMs);
  const label = `Duration over the last ${ran.length} results on ${row.engine}: shortest ${formatDuration(Math.min(...durations))}, longest ${formatDuration(Math.max(...durations))}, latest ${formatDuration(durations[0] ?? 0)}`;
  return (
    <svg
      width={SPARK_W}
      height={SPARK_H}
      viewBox={`0 0 ${SPARK_W} ${SPARK_H}`}
      role="img"
      aria-label={label}
    >
      <polyline
        points={sparklinePoints(row.results)}
        fill="none"
        stroke="currentColor"
        strokeWidth={1.5}
        className="text-info"
      />
    </svg>
  );
}

function EngineRow({ row }: { row: EngineHistory }) {
  return (
    <section
      aria-label={`${row.engine} history`}
      className="flex flex-col gap-3 rounded-panel border border-hairline bg-surface p-4"
    >
      <div className="flex flex-wrap items-center gap-3">
        <EngineChip engine={row.engine} />
        <HealthBadge health={row.health} />
        <span className="text-body-sm text-text-secondary">
          {row.health
            ? `${row.health.reason} · last ${row.health.observed} of ${row.health.windowSize}`
            : 'No results yet on this engine.'}
        </span>
      </div>
      {row.results.length > 0 ? (
        <div className="flex flex-wrap items-center gap-4">
          <OutcomeStrip row={row} />
          <Sparkline row={row} />
        </div>
      ) : null}
    </section>
  );
}

export function CaseHistoryPage() {
  const { caseKey = '' } = useParams();
  const [search] = useSearchParams();
  const suiteKey = search.get('suiteKey');
  const history = useCaseHistory(caseKey, suiteKey);
  const data = history.data;

  return (
    <Page
      title={`Case history · ${caseKey}`}
      description={data ? `${data.title} · ${data.suiteKey}` : undefined}
    >
      {!suiteKey ? (
        <EmptyState title="Which suite?">
          A case key belongs to a suite. Open the history from a case in a run, or add{' '}
          <code className="font-mono">?suiteKey=</code> to the address.
        </EmptyState>
      ) : null}
      {suiteKey && history.isPending ? <LoadingState label="Loading case history…" /> : null}
      {history.error ? <ErrorState error={history.error} /> : null}
      {data ? (
        <>
          <div className="flex flex-col gap-3">
            {data.engines.map((row) => (
              <EngineRow key={row.engine} row={row} />
            ))}
          </div>
          <p className="text-body-sm text-text-muted">
            Squares link to their run, newest on the right; dashed squares mean no result yet. ×
            failed, T timed out, ~ passed only on a retry, S skipped. A label needs at least 5
            results and is computed from the last 20 that ran (skipped results don&apos;t count).
          </p>
        </>
      ) : null}
    </Page>
  );
}

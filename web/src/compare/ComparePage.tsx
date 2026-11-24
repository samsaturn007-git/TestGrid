import { Link, useSearchParams } from 'react-router';
import { useComparison } from '../api/queries.ts';
import type {
  ComparisonCategory,
  ComparisonRow,
  ComparisonRun,
  RunComparisonView,
} from '../api/types.ts';
import { EmptyState, ErrorState, LoadingState } from '../components/States.tsx';
import { StatusBadge } from '../components/StatusBadge.tsx';
import { formatDuration } from '../lib/time.ts';
import { Page } from '../pages/Page.tsx';

// PRD-07 FR-6: two runs side by side, grouped by what changed (contracts §8). Fingerprint
// changes and duration regressions are called out; every row says why it is in its group.

const ORDER: ComparisonCategory[] = [
  'NEW_FAILURE',
  'FIXED',
  'STILL_FAILING',
  'FLAKY_IN_HEAD',
  'ADDED',
  'REMOVED',
  'NOT_COMPARABLE',
  'STILL_PASSING',
];

const MEANING: Record<ComparisonCategory, string> = {
  NEW_FAILURE: 'passed in base, fails in head',
  FIXED: 'failed in base, passes in head',
  STILL_FAILING: 'fails in both',
  FLAKY_IN_HEAD: 'passed only on a retry in head, or its history is labelled FLAKY',
  ADDED: 'in the head run only',
  REMOVED: 'in the base run only',
  NOT_COMPARABLE: 'a target is missing, failed to run, or the case was skipped',
  STILL_PASSING: 'passes in both',
};

const TONE: Record<ComparisonCategory, string> = {
  NEW_FAILURE: 'text-danger',
  FIXED: 'text-success',
  STILL_FAILING: 'text-danger',
  FLAKY_IN_HEAD: 'text-flaky',
  ADDED: 'text-info',
  REMOVED: 'text-text-muted',
  NOT_COMPARABLE: 'text-text-muted',
  STILL_PASSING: 'text-text-muted',
};

function RunSide({ label, run }: { label: string; run: ComparisonRun }) {
  const source = [run.branch, run.commitSha?.slice(0, 7)].filter(Boolean).join(' @ ');
  const fixture = run.fixtureMode ? `${run.fixtureMode} · ${run.fixtureSeed ?? ''}` : null;
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <span className="font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted">
        {label}
      </span>
      <span className="flex flex-wrap items-center gap-2 font-mono text-mono">
        <StatusBadge kind="run" status={run.status} />
        <Link to={`/runs/${run.id}`}>{run.id.slice(0, 8)}</Link>
        {source ? <span className="text-text-secondary">{source}</span> : null}
        {fixture ? <span className="text-text-muted">{fixture}</span> : null}
      </span>
    </div>
  );
}

function durationText(row: ComparisonRow): string | null {
  if (row.baseDurationMs === null || row.headDurationMs === null) return null;
  return `${formatDuration(row.baseDurationMs)} → ${formatDuration(row.headDurationMs)}`;
}

function Row({ row, suiteKey }: { row: ComparisonRow; suiteKey: string }) {
  const duration = durationText(row);
  return (
    <li className="grid grid-cols-[minmax(0,200px)_90px_minmax(0,1fr)] items-baseline gap-x-3 gap-y-1 px-4 py-2 pl-9 font-mono text-mono-sm text-text-secondary">
      <Link
        to={`/cases/${encodeURIComponent(row.caseKey)}?suiteKey=${encodeURIComponent(suiteKey)}`}
        className="truncate text-text"
      >
        {row.caseKey}
      </Link>
      <span>{row.engine}</span>
      <span className="flex flex-wrap items-center gap-2">
        <span>
          {row.baseStatus ?? '—'} → {row.headStatus ?? '—'}
        </span>
        {duration ? <span className="text-text-muted">{duration}</span> : null}
        {row.durationRegression ? (
          <span className="rounded-tag border border-warning-rim bg-warning-tint px-1.5 text-warning">
            ▲ slower
          </span>
        ) : null}
        {row.fingerprintChanged ? (
          <span className="rounded-tag border border-danger-rim bg-danger-tint px-1.5 text-danger">
            fingerprint changed
          </span>
        ) : null}
        {row.reason ? <span className="font-sans text-text-muted">{row.reason}</span> : null}
      </span>
    </li>
  );
}

function Group({
  category,
  rows,
  suiteKey,
}: {
  category: ComparisonCategory;
  rows: ComparisonRow[];
  suiteKey: string;
}) {
  // Everything that changed is open; the unchanged and the empty stay folded.
  const open = rows.length > 0 && category !== 'STILL_PASSING';
  return (
    <details open={open} className="border-b border-hairline-soft last:border-b-0">
      <summary className="flex cursor-pointer items-center gap-2.5 bg-canvas px-4 py-2.5">
        <span className={`font-mono text-mono-sm font-medium ${TONE[category]}`}>{category}</span>
        <span className="font-mono text-mono-sm font-semibold">{rows.length}</span>
        <span className="text-body-sm text-text-muted">{MEANING[category]}</span>
      </summary>
      {rows.length > 0 ? (
        <ul aria-label={`${category} rows`}>
          {rows.map((row) => (
            <Row key={`${row.caseKey}-${row.engine}`} row={row} suiteKey={suiteKey} />
          ))}
        </ul>
      ) : null}
    </details>
  );
}

function Comparison({ data }: { data: RunComparisonView }) {
  const byCategory = new Map<ComparisonCategory, ComparisonRow[]>(ORDER.map((c) => [c, []]));
  for (const row of data.rows) byCategory.get(row.category)?.push(row);
  const slower = data.rows.filter((r) => r.durationRegression);
  const baseLabel = data.baseIsLastGreen
    ? `Base · last green${data.base.branch ? ` on ${data.base.branch}` : ''}`
    : 'Base';
  return (
    <>
      <section
        aria-label="Runs compared"
        className="grid grid-cols-1 gap-3 rounded-panel border border-hairline bg-surface p-4 sm:grid-cols-[minmax(0,1fr)_32px_minmax(0,1fr)] sm:items-center"
      >
        <RunSide label={baseLabel} run={data.base} />
        <span aria-hidden="true" className="hidden text-center text-text-muted sm:block">
          →
        </span>
        <RunSide label="Head" run={data.head} />
      </section>
      {data.sameRevision ? null : (
        <p role="note" className="text-body-sm text-text-secondary">
          The suite revisions differ ({data.base.revision} → {data.head.revision}), so some cases
          may be ADDED or REMOVED.
        </p>
      )}
      <section
        aria-label="Changes by category"
        className="overflow-hidden rounded-panel border border-hairline bg-surface"
      >
        {ORDER.map((category) => (
          <Group
            key={category}
            category={category}
            rows={byCategory.get(category) ?? []}
            suiteKey={data.head.suiteKey}
          />
        ))}
      </section>
      <section aria-labelledby="regressions" className="flex flex-col gap-1.5">
        <h2
          id="regressions"
          className="font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted"
        >
          Duration regressions · head slower than base × {data.thresholds.regressionRatio} and base
          + {data.thresholds.regressionMinDeltaMs} ms
        </h2>
        {slower.length === 0 ? (
          <p className="text-body-sm text-text-muted">None.</p>
        ) : (
          <ul className="flex flex-col gap-1 font-mono text-mono-sm text-warning">
            {slower.map((r) => (
              <li key={`${r.caseKey}-${r.engine}`}>
                ▲ {r.engine} · {r.caseKey} · {durationText(r)}
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

/** `/compare?base=<runId | last-green>&head=<runId>` (PRD-07 FR-6). */
export function ComparePage() {
  const [search] = useSearchParams();
  const base = search.get('base');
  const head = search.get('head');
  const comparison = useComparison(base, head);
  return (
    <Page title="Compare runs" description="What changed between two runs of one suite.">
      {!base || !head ? (
        <EmptyState title="Choose two runs">
          Open a run and use “Compare with last green”, or add{' '}
          <code className="font-mono">?base=&lt;run&gt;&amp;head=&lt;run&gt;</code> to the address.
        </EmptyState>
      ) : null}
      {base && head && comparison.isPending ? <LoadingState label="Comparing runs…" /> : null}
      {comparison.error ? <ErrorState error={comparison.error} /> : null}
      {comparison.data ? <Comparison data={comparison.data} /> : null}
    </Page>
  );
}

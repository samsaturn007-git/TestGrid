import type { ReactNode } from 'react';
import { Link } from 'react-router';
import {
  OVERVIEW_REFRESH_MS,
  useCurrentProject,
  useRecentRuns,
  useRunCounts,
  useWorkers,
} from '../api/queries.ts';
import type { RunSummary } from '../api/types.ts';
import { primaryButton } from '../components/buttons.ts';
import { CapacityMeter, WorkerStateCounts } from '../components/Capacity.tsx';
import { RefreshNotice } from '../components/RefreshNotice.tsx';
import { ErrorState, LoadingState } from '../components/States.tsx';
import { StatusBadge } from '../components/StatusBadge.tsx';
import { formatAbsolute, formatAgo, formatDuration, useNow } from '../lib/time.ts';
import { useNewRun } from '../runs/newRunContext.ts';
import { Page } from './Page.tsx';

/**
 * `/` (PRD-05 FR-2): run status counts for the last 24 h, recent runs, workers online. Every
 * number is one the API returned; a part that fails says so instead of showing a zero.
 */
export function OverviewPage() {
  const { project } = useCurrentProject();
  const counts = useRunCounts(project?.id);
  const recent = useRecentRuns(8);
  const workers = useWorkers(OVERVIEW_REFRESH_MS);
  const now = useNow();
  const refreshFailed = counts.isRefetchError || recent.isRefetchError || workers.isRefetchError;
  const updatedAt = Math.min(
    ...[counts.dataUpdatedAt, recent.dataUpdatedAt, workers.dataUpdatedAt].filter((t) => t > 0),
  );

  return (
    <Page
      title="Overview"
      description={
        counts.data ? (
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span>
              Last {counts.data.hours} h · since{' '}
              <time dateTime={counts.data.from}>{formatAbsolute(counts.data.from)}</time> ·{' '}
              {counts.data.total} {counts.data.total === 1 ? 'run' : 'runs'}
            </span>
            {!refreshFailed && Number.isFinite(updatedAt) ? (
              <RefreshNotice updatedAt={updatedAt} failed={false} onRetry={() => undefined} />
            ) : null}
          </span>
        ) : undefined
      }
    >
      {refreshFailed && Number.isFinite(updatedAt) ? (
        <RefreshNotice
          updatedAt={updatedAt}
          failed
          onRetry={() => {
            void counts.refetch();
            void recent.refetch();
            void workers.refetch();
          }}
        />
      ) : null}

      <section aria-labelledby="counts-h" className="flex flex-col gap-3">
        <h2
          id="counts-h"
          className="font-mono text-[11px] font-medium uppercase tracking-[0.08em] text-text-muted"
        >
          Run status · last 24 h
        </h2>
        {counts.isPending ? <LoadingState label="Loading run counts…" /> : null}
        {counts.error && !counts.data ? <ErrorState error={counts.error} /> : null}
        {counts.data ? (
          <ul className="grid grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-3">
            {counts.data.items.map(({ status, count }) => (
              <li
                key={status}
                aria-label={`${count} ${status} ${count === 1 ? 'run' : 'runs'} in the last ${counts.data.hours} hours`}
                className="flex flex-col gap-3 rounded-panel border border-hairline bg-surface p-4"
              >
                <StatusBadge kind="run" status={status} animate={false} />
                <span
                  className={`font-mono text-[32px] font-semibold leading-none ${count === 0 ? 'text-text-muted' : ''}`}
                >
                  {count}
                </span>
              </li>
            ))}
          </ul>
        ) : null}
      </section>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,2fr)_minmax(320px,1fr)]">
        <Panel id="recent-h" title="Recent runs" link={{ to: '/runs', label: 'All runs' }}>
          {recent.isPending ? <LoadingState label="Loading recent runs…" /> : null}
          {recent.error && !recent.data ? <ErrorState error={recent.error} /> : null}
          {recent.data && recent.data.items.length === 0 ? <NoRunsYet /> : null}
          {recent.data && recent.data.items.length > 0 ? (
            <ul aria-labelledby="recent-h" className="flex flex-col">
              {recent.data.items.map((run) => (
                <RecentRun key={run.id} run={run} now={now} />
              ))}
            </ul>
          ) : null}
        </Panel>

        <Panel id="workers-h" title="Workers" link={{ to: '/workers', label: 'Open workers' }}>
          {workers.isPending ? <LoadingState label="Loading workers…" /> : null}
          {workers.error && !workers.data ? <ErrorState error={workers.error} /> : null}
          {workers.data ? (
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <span className="font-mono text-[11px] font-medium uppercase tracking-[0.08em] text-text-muted">
                  Active sessions / cap
                </span>
                <span className="font-mono text-[28px] font-semibold leading-none">
                  {workers.data.activeSessions}{' '}
                  <span className="font-normal text-text-muted">
                    / {workers.data.globalMaxSessions}
                  </span>
                </span>
                <CapacityMeter workers={workers.data} />
                <span className="text-body-sm text-text-muted">
                  {workers.data.dispatchedUnclaimed} dispatched, not yet claimed
                </span>
              </div>
              <WorkerStateCounts workers={workers.data} />
              {workers.data.items.every((w) => w.state === 'LOST') ? (
                <p className="text-body-sm text-text-muted">
                  No worker is online. Queued targets wait until one registers.
                </p>
              ) : null}
            </div>
          ) : null}
        </Panel>
      </div>
    </Page>
  );
}

function Panel({
  id,
  title,
  link,
  children,
}: {
  id: string;
  title: string;
  link: { to: string; label: string };
  children: ReactNode;
}) {
  return (
    <section aria-labelledby={id} className="flex min-w-0 flex-col gap-3">
      <div className="flex items-baseline justify-between gap-3">
        <h2 id={id} className="text-heading">
          {title}
        </h2>
        <Link to={link.to} className="text-body-sm">
          {link.label} <span aria-hidden="true">→</span>
        </Link>
      </div>
      <div className="rounded-panel border border-hairline bg-surface p-4">{children}</div>
    </section>
  );
}

function NoRunsYet() {
  const { openNewRun } = useNewRun();
  return (
    <div className="flex flex-col items-start gap-3 text-body text-text-secondary">
      <span>No runs yet. Runs appear here as soon as one is created.</span>
      <button
        type="button"
        className={primaryButton}
        aria-haspopup="dialog"
        onClick={() => openNewRun()}
      >
        Start a run
      </button>
    </div>
  );
}

function RecentRun({ run, now }: { run: RunSummary; now: number }) {
  const source = [run.source?.branch, run.source?.commitSha?.slice(0, 7)]
    .filter(Boolean)
    .join(' · ');
  return (
    <li className="grid grid-cols-[110px_minmax(0,1fr)_auto_auto] items-center gap-3 border-b border-hairline-soft py-2 last:border-b-0">
      <StatusBadge kind="run" status={run.status} size="dense" />
      <span className="flex min-w-0 flex-col">
        <Link to={`/runs/${run.id}`} className="truncate font-mono text-mono">
          {run.suiteKey} <span className="text-text-muted">#{run.id.slice(0, 8)}</span>
        </Link>
        <span className="truncate text-body-sm text-text-muted">
          {run.trigger}
          {source ? ` · ${source}` : ''}
          {run.fixture ? ` · ${run.fixture.mode}` : ''}
        </span>
      </span>
      <time
        dateTime={run.queuedAt}
        title={formatAbsolute(run.queuedAt)}
        className="text-body-sm whitespace-nowrap text-text-muted"
      >
        {formatAgo(run.queuedAt, now)}
      </time>
      <span className="w-20 text-right font-mono text-mono-sm whitespace-nowrap">
        {run.startedAt && run.finishedAt
          ? formatDuration(Date.parse(run.finishedAt) - Date.parse(run.startedAt))
          : '—'}
      </span>
    </li>
  );
}

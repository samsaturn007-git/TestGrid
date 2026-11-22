import { useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { ApiError } from '../../api/client.ts';
import { isTerminal, useCancelRun, useRerun } from '../../api/queries.ts';
import type { RerunScope, RunResponse } from '../../api/types.ts';
import { primaryButton, secondaryButton } from '../../components/buttons.ts';
import { ErrorState } from '../../components/States.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { formatAbsolute, formatClock, formatDuration } from '../../lib/time.ts';
import { failedTargets, summarySentence, type RunTally } from './model.ts';

/**
 * Status, what ran, and the two actions (PRD-05 FR-2): Cancel while the run is unfinished,
 * Rerun once it has finished. Each action states its effect before it happens.
 */
export function RunHeader({
  run,
  title,
  tally,
  live,
}: {
  run: RunResponse;
  title: string;
  tally: RunTally;
  live: boolean;
}) {
  const terminal = isTerminal(run.status);
  return (
    <section aria-labelledby="run-title" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 flex-col gap-2">
          <div className="flex flex-wrap items-center gap-3">
            <StatusBadge kind="run" status={run.status} animate={live} />
            <h1 id="run-title" className="text-title">
              {title}
            </h1>
            <span className="font-mono text-mono text-text-secondary">
              {run.suiteKey} @ {run.suiteRevision}
            </span>
          </div>
          <p className="text-body text-text-secondary">{summarySentence(tally)}</p>
          {!terminal ? (
            <p className="text-body-sm text-text-muted">
              {live
                ? 'Refreshing every 5 s until the run finishes.'
                : 'Not refreshing: the last refresh failed.'}
            </p>
          ) : null}
        </div>
        {terminal ? <RerunActions run={run} /> : <CancelAction run={run} />}
      </div>

      <dl className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-x-6 gap-y-3 rounded-panel border border-hairline bg-surface px-5 py-4">
        <Meta term="Trigger" value={run.trigger} mono />
        <Meta
          term="Fixture"
          value={run.fixture ? `${run.fixture.mode} · seed ${run.fixture.seed}` : 'none'}
          mono
          muted={!run.fixture}
        />
        <Meta
          term="Source"
          value={
            run.source?.branch || run.source?.commitSha
              ? [run.source.branch, run.source.commitSha?.slice(0, 12)].filter(Boolean).join(' · ')
              : 'none recorded'
          }
          mono
          muted={!run.source?.branch && !run.source?.commitSha}
        />
        <TimeMeta term="Queued" iso={run.queuedAt} />
        <TimeMeta term="Started" iso={run.startedAt} />
        <TimeMeta term="Finished" iso={run.finishedAt} />
        <Meta
          term="Duration"
          value={
            run.startedAt && run.finishedAt
              ? formatDuration(Date.parse(run.finishedAt) - Date.parse(run.startedAt))
              : '—'
          }
          mono
          muted={!run.finishedAt}
        />
        <Meta term="Engines" value={run.targets.map((t) => t.engine).join(' · ')} mono />
      </dl>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-body-sm text-text-muted">
        <span>
          Run ID <code className="select-all font-mono text-text-secondary">{run.id}</code>
        </span>
        {run.parentRunId ? (
          <span>
            Rerun of{' '}
            <Link to={`/runs/${run.parentRunId}`} className="font-mono">
              {run.parentRunId.slice(0, 8)}
            </Link>
          </span>
        ) : null}
        {run.rerunIds.length > 0 ? (
          <span className="flex flex-wrap items-center gap-1.5">
            {run.rerunIds.length === 1 ? 'Rerun as' : 'Rerun as, oldest first:'}
            {run.rerunIds.map((id) => (
              <Link key={id} to={`/runs/${id}`} className="font-mono">
                {id.slice(0, 8)}
              </Link>
            ))}
          </span>
        ) : null}
      </div>
    </section>
  );
}

function Meta({
  term,
  value,
  mono = false,
  muted = false,
}: {
  term: string;
  value: string;
  mono?: boolean;
  muted?: boolean;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <dt className="font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted">
        {term}
      </dt>
      <dd
        className={`truncate ${mono ? 'font-mono text-mono' : 'text-body'} ${muted ? 'text-text-muted' : ''}`}
      >
        {value}
      </dd>
    </div>
  );
}

function TimeMeta({ term, iso }: { term: string; iso: string | null }) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <dt className="font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted">
        {term}
      </dt>
      <dd className="font-mono text-mono">
        {iso ? (
          <time dateTime={iso} title={formatAbsolute(iso)}>
            {formatClock(iso)}
          </time>
        ) : (
          <span className="text-text-muted">—</span>
        )}
      </dd>
    </div>
  );
}

function CancelAction({ run }: { run: RunResponse }) {
  const cancel = useCancelRun(run.id);
  const [confirming, setConfirming] = useState(false);
  const cancelButton = useRef<HTMLButtonElement>(null);

  if (run.cancelRequestedAt) {
    return (
      <p role="status" className="max-w-[360px] text-body text-text-secondary">
        Cancel requested at{' '}
        <time dateTime={run.cancelRequestedAt} title={formatAbsolute(run.cancelRequestedAt)}>
          {formatClock(run.cancelRequestedAt)}
        </time>
        . Running targets stop at their next heartbeat.
      </p>
    );
  }
  if (!confirming) {
    return (
      <button
        ref={cancelButton}
        type="button"
        className={secondaryButton}
        onClick={() => setConfirming(true)}
      >
        Cancel run
      </button>
    );
  }
  return (
    <div
      role="group"
      aria-labelledby="cancel-confirm"
      className="flex max-w-[420px] flex-col gap-3 rounded-card border border-hairline-strong bg-surface p-3"
    >
      <p id="cancel-confirm" className="text-body">
        Cancel this run? Queued targets end CANCELLED now; running targets stop at their next
        heartbeat unless they finish first.
      </p>
      {cancel.error ? <ErrorState error={cancel.error} /> : null}
      <div className="flex gap-2">
        <button
          type="button"
          // Focus moves to the confirmation when it appears, so keyboard users land on it.
          autoFocus
          className={primaryButton}
          aria-busy={cancel.isPending}
          aria-disabled={cancel.isPending}
          onClick={() => {
            if (!cancel.isPending) cancel.mutate();
          }}
        >
          {cancel.isPending ? 'Cancelling…' : 'Yes, cancel run'}
        </button>
        <button
          type="button"
          className={secondaryButton}
          disabled={cancel.isPending}
          onClick={() => setConfirming(false)}
        >
          Keep running
        </button>
      </div>
    </div>
  );
}

function RerunActions({ run }: { run: RunResponse }) {
  const rerun = useRerun(run.id);
  const navigate = useNavigate();
  // One key per scope for the life of this page: a retry after a lost answer replays the same
  // rerun instead of starting a second one.
  const keys = useRef<Partial<Record<RerunScope, string>>>({});
  const failed = failedTargets(run);

  function start(scope: RerunScope) {
    if (rerun.isPending) return;
    const idempotencyKey = (keys.current[scope] ??= crypto.randomUUID());
    rerun.mutate(
      { scope, idempotencyKey },
      { onSuccess: (result) => void navigate(`/runs/${result.data.id}`) },
    );
  }

  const problem = rerun.error instanceof ApiError ? rerun.error : null;
  return (
    <div className="flex max-w-[420px] flex-col items-end gap-2">
      <div className="flex flex-wrap justify-end gap-2">
        <button
          type="button"
          className={secondaryButton}
          aria-disabled={rerun.isPending}
          onClick={() => start('ALL')}
        >
          Rerun all
        </button>
        {failed.length > 0 ? (
          <button
            type="button"
            className={primaryButton}
            aria-disabled={rerun.isPending}
            aria-busy={rerun.isPending && rerun.variables?.scope === 'FAILED_TARGETS'}
            onClick={() => start('FAILED_TARGETS')}
          >
            Rerun failed targets ({failed.map((t) => t.engine).join(', ')})
          </button>
        ) : null}
      </div>
      {rerun.isPending ? (
        <p role="status" className="text-body-sm text-text-muted">
          Starting the rerun…
        </p>
      ) : null}
      {rerun.error ? (
        <div className="w-full">
          <ErrorState error={problem ?? rerun.error} />
        </div>
      ) : null}
    </div>
  );
}

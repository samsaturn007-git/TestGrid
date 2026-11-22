import { Link, useParams } from 'react-router';
import { isTerminal, useRun, useRunResults, useSuite } from '../../api/queries.ts';
import type { RunResponse } from '../../api/types.ts';
import { secondaryButton } from '../../components/buttons.ts';
import { ErrorState, LoadingState } from '../../components/States.tsx';
import { formatAbsolute } from '../../lib/time.ts';
import { AttemptsList } from './AttemptsList.tsx';
import { BrowserMatrix } from './BrowserMatrix.tsx';
import { CaseTable } from './CaseTable.tsx';
import { buildMatrix, tally } from './model.ts';
import { RunHeader } from './RunHeader.tsx';

/**
 * Changes whenever anything the results depend on changes, so results are refetched exactly
 * when the run moves on, not on a timer of their own.
 */
function resultsVersion(run: RunResponse): string {
  return [
    run.status,
    ...run.targets.map(
      (t) => `${t.id}:${t.status}:${t.attemptCount}:${t.latestAttempt?.status ?? '-'}`,
    ),
  ].join('|');
}

/** `/runs/:runId` (PRD-05 FR-2): header and actions, browser matrix, attempts, cases. */
export function RunDetailPage() {
  const { runId = '' } = useParams();
  const run = useRun(runId);
  const data = run.data;
  const results = useRunResults(runId, data ? resultsVersion(data) : undefined);
  const suite = useSuite(data?.suiteId ?? '', data !== undefined);
  const shortId = runId.slice(0, 8);

  if (!data) {
    return (
      <>
        <title>{`Run ${shortId} · TestGrid`}</title>
        <h1 className="text-title">Run {shortId}</h1>
        {run.isPending ? <LoadingState label="Loading run…" /> : null}
        {run.error ? (
          <ErrorState
            error={run.error}
            action={
              <Link to="/runs" className={secondaryButton}>
                Back to runs
              </Link>
            }
          />
        ) : null}
      </>
    );
  }

  const terminal = isTerminal(data.status);
  // A failed refresh of an unfinished run means what is shown may be out of date: say so, and
  // stop animating RUNNING so it doesn't look live.
  const refreshFailed = run.isRefetchError;
  const live = !terminal && !refreshFailed;
  const { columns, rows } = buildMatrix(data, results.data, suite.data?.cases);

  return (
    <>
      <title>{`Run ${shortId} · ${data.status} · TestGrid`}</title>
      {refreshFailed ? (
        <p
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-card border border-warning-rim bg-warning-tint px-3 py-2 text-body"
        >
          <span>
            Could not refresh this run. What you see was loaded at{' '}
            <time dateTime={new Date(run.dataUpdatedAt).toISOString()}>
              {formatAbsolute(new Date(run.dataUpdatedAt).toISOString())}
            </time>
            .
          </span>
          <button type="button" className={secondaryButton} onClick={() => void run.refetch()}>
            Try again
          </button>
        </p>
      ) : null}
      <RunHeader
        run={data}
        title={suite.data?.suite.displayName ?? data.suiteKey}
        tally={tally(data, results.data)}
        live={live}
      />
      {results.error && !results.data ? <ErrorState error={results.error} /> : null}
      <BrowserMatrix columns={columns} rows={rows} live={live} casesLoading={suite.isPending} />
      <AttemptsList targets={data.targets} results={results.data} live={live} />
      <CaseTable targets={data.targets} results={results.data} cases={suite.data?.cases} />
    </>
  );
}

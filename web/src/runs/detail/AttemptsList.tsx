import type { RunResults, RunTarget } from '../../api/types.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { formatAbsolute, formatClock } from '../../lib/time.ts';

/** Every attempt of every target, newest first. Retries never replace earlier attempts. */
export function AttemptsList({
  targets,
  results,
  live,
}: {
  targets: RunTarget[];
  results: RunResults | undefined;
  live: boolean;
}) {
  return (
    <section aria-labelledby="attempts-h" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="attempts-h" className="text-heading">
          Targets and attempts
        </h2>
        <span className="text-body-sm text-text-muted">
          Retries never overwrite: every attempt stays in the history.
        </span>
      </div>
      <div className="grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-3">
        {targets.map((target) => {
          const attempts = results?.targets.find((t) => t.id === target.id)?.attempts ?? [];
          const headingId = `target-${target.id}`;
          return (
            <article
              key={target.id}
              aria-labelledby={headingId}
              className="flex flex-col gap-3 rounded-panel border border-hairline bg-surface p-4"
            >
              <div className="flex items-baseline justify-between gap-2">
                <h3 id={headingId} className="font-mono text-mono font-medium">
                  {target.targetKey}
                </h3>
                <span className="text-body-sm text-text-muted">
                  {attempts.length === 1 ? '1 attempt' : `${attempts.length} attempts`} of{' '}
                  {target.maxAttempts}
                </span>
              </div>
              {attempts.length === 0 ? (
                <p className="text-body-sm text-text-muted">
                  No attempt yet. The target is{' '}
                  <StatusBadge kind="target" status={target.status} size="dense" animate={live} />
                </p>
              ) : (
                <ol className="flex flex-col gap-3">
                  {attempts.map(({ attempt, errorDetail }) => (
                    <li
                      key={attempt.id}
                      className="flex flex-col gap-2 border-t border-hairline-soft pt-3 first:border-t-0 first:pt-0"
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-mono text-mono-sm text-text-muted">
                          #{attempt.attemptNo}
                        </span>
                        <StatusBadge
                          kind="attempt"
                          status={attempt.status}
                          size="dense"
                          animate={live}
                        />
                        {attempt.errorClass ? (
                          <span className="font-mono text-mono-sm">{attempt.errorClass}</span>
                        ) : null}
                        {attempt.leaseExpired ? (
                          <span className="rounded-tag border border-warning-rim px-1.5 text-body-sm text-warning">
                            lease expired{' '}
                            <time dateTime={attempt.leasedUntil}>
                              {formatClock(attempt.leasedUntil)}
                            </time>
                          </span>
                        ) : null}
                      </div>
                      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-body-sm">
                        <dt className="text-text-muted">worker</dt>
                        <dd className="truncate font-mono">{attempt.workerId}</dd>
                        <Stamp term="claimed" iso={attempt.claimedAt} />
                        <Stamp term="started" iso={attempt.startedAt} />
                        <Stamp term="finished" iso={attempt.finishedAt} />
                        <Stamp term="last heartbeat" iso={attempt.lastHeartbeatAt} />
                      </dl>
                      {errorDetail ? (
                        <pre className="max-h-32 overflow-auto whitespace-pre-wrap rounded-chip bg-canvas p-2 font-mono text-mono-sm text-text-secondary">
                          {errorDetail}
                        </pre>
                      ) : null}
                    </li>
                  ))}
                </ol>
              )}
            </article>
          );
        })}
      </div>
    </section>
  );
}

function Stamp({ term, iso }: { term: string; iso: string | null }) {
  return (
    <>
      <dt className="text-text-muted">{term}</dt>
      <dd className="font-mono">
        {iso ? (
          <time dateTime={iso} title={formatAbsolute(iso)}>
            {formatClock(iso)}
          </time>
        ) : (
          <span className="text-text-muted">—</span>
        )}
      </dd>
    </>
  );
}

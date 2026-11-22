import type { ReactNode } from 'react';
import { useWorkers } from '../api/queries.ts';
import type { WorkerView } from '../api/types.ts';
import { CapacityMeter, WorkerStateCounts } from '../components/Capacity.tsx';
import { EngineChip } from '../components/Engine.tsx';
import { RefreshNotice } from '../components/RefreshNotice.tsx';
import { EmptyState, ErrorState, LoadingState } from '../components/States.tsx';
import { StatusBadge } from '../components/StatusBadge.tsx';
import { formatAbsolute, formatAgo, formatClock, useNow } from '../lib/time.ts';
import { Page } from './Page.tsx';

/** `/workers` (PRD-05 FR-2): the registry with state, slots in use and time since last seen. */
export function WorkersPage() {
  const workers = useWorkers();
  const now = useNow(1_000);
  const data = workers.data;
  const live = data?.items.filter((w) => w.state !== 'LOST') ?? [];
  const lost = data?.items.filter((w) => w.state === 'LOST') ?? [];

  return (
    <Page
      title="Workers"
      description={
        data ? (
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span>
              {live.length} online · {lost.length} LOST in the last hour
            </span>
            <RefreshNotice
              updatedAt={workers.dataUpdatedAt}
              failed={false}
              onRetry={() => void workers.refetch()}
            />
          </span>
        ) : undefined
      }
    >
      {workers.isPending ? <LoadingState label="Loading workers…" /> : null}
      {workers.error && !data ? <ErrorState error={workers.error} /> : null}
      {data && workers.isRefetchError ? (
        <RefreshNotice
          updatedAt={workers.dataUpdatedAt}
          failed
          onRetry={() => void workers.refetch()}
        />
      ) : null}
      {data ? (
        <>
          <section
            aria-label="Capacity"
            className="grid grid-cols-[repeat(auto-fit,minmax(240px,1fr))] gap-3"
          >
            <Card title="Active sessions / global cap">
              <span className="font-mono text-[32px] font-semibold leading-none">
                {data.activeSessions}{' '}
                <span className="font-normal text-text-muted">/ {data.globalMaxSessions}</span>
              </span>
              <CapacityMeter workers={data} />
            </Card>
            <Card title="Dispatched, not yet claimed">
              <span className="font-mono text-[32px] font-semibold leading-none">
                {data.dispatchedUnclaimed}
              </span>
              <span className="text-body-sm text-text-muted">
                Targets handed to the queue that no worker has claimed yet.
              </span>
            </Card>
            <Card title="Workers by state">
              <WorkerStateCounts workers={data} />
            </Card>
          </section>

          {data.items.length === 0 ? (
            <EmptyState title="No worker has registered">
              Workers register with the API when they start (
              <code className="font-mono">make up</code> starts one). Queued targets wait until one
              does.
            </EmptyState>
          ) : (
            <div className="overflow-x-auto rounded-panel border border-hairline bg-surface">
              <table className="w-full min-w-[960px] border-collapse text-left">
                <caption className="sr-only">
                  Workers, live first, then LOST in the last hour
                </caption>
                <thead className="bg-canvas">
                  <tr className="border-b border-hairline font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted">
                    <th scope="col" className="py-2.5 pl-4 pr-3 font-medium">
                      Worker
                    </th>
                    <th scope="col" className="py-2.5 pr-3 font-medium">
                      State
                    </th>
                    <th scope="col" className="py-2.5 pr-3 font-medium">
                      Engines
                    </th>
                    <th scope="col" className="py-2.5 pr-3 text-right font-medium">
                      Slots in use
                    </th>
                    <th scope="col" className="py-2.5 pr-3 font-medium">
                      Version
                    </th>
                    <th scope="col" className="py-2.5 pr-3 font-medium">
                      Started
                    </th>
                    <th scope="col" className="py-2.5 pr-4 font-medium">
                      Last seen
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {live.map((w) => (
                    <WorkerRow key={w.id} worker={w} now={now} />
                  ))}
                  {lost.length > 0 ? (
                    <tr className="border-b border-hairline-soft bg-canvas">
                      <th
                        scope="rowgroup"
                        colSpan={7}
                        className="py-2 pl-4 text-left font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted"
                      >
                        LOST in the last hour
                      </th>
                    </tr>
                  ) : null}
                  {lost.map((w) => (
                    <WorkerRow key={w.id} worker={w} now={now} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="text-body-sm text-text-muted">
            The cap is global: a worker with a free slot still waits while active sessions are at
            the cap. A worker becomes LOST when it stops sending heartbeats.
          </p>
        </>
      ) : null}
    </Page>
  );
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-3 rounded-panel border border-hairline bg-surface p-4">
      <h2 className="font-mono text-[11px] font-medium uppercase tracking-[0.08em] text-text-muted">
        {title}
      </h2>
      {children}
    </div>
  );
}

function WorkerRow({ worker: w, now }: { worker: WorkerView; now: number }) {
  return (
    <tr
      className={`h-[50px] border-b border-hairline-soft last:border-b-0 ${w.state === 'LOST' ? 'text-text-secondary' : ''}`}
    >
      <th scope="row" className="py-2 pl-4 pr-3 text-left font-normal">
        <span className="flex flex-col gap-0.5">
          <span className="font-mono text-mono font-medium">{w.name}</span>
          <span className="text-body-sm text-text-muted">
            {w.state === 'LOST'
              ? 'no heartbeat; its attempts are recovered by the reaper'
              : w.state === 'DRAINING'
                ? 'finishing its attempts, takes no new work'
                : w.activeAttempts === 0
                  ? 'idle'
                  : `running ${w.activeAttempts} ${w.activeAttempts === 1 ? 'attempt' : 'attempts'}`}
          </span>
        </span>
      </th>
      <td className="py-2 pr-3">
        <StatusBadge kind="worker" status={w.state} />
      </td>
      <td className="py-2 pr-3">
        <span className="flex flex-wrap gap-1">
          {w.engines.map((e) => (
            <EngineChip key={e} engine={e} />
          ))}
        </span>
      </td>
      <td className="py-2 pr-3 text-right font-mono text-mono">
        {w.activeAttempts} / {w.slots}
      </td>
      <td className="py-2 pr-3 font-mono text-mono-sm">{w.version}</td>
      <td className="py-2 pr-3 font-mono text-mono-sm">
        <time dateTime={w.startedAt} title={formatAbsolute(w.startedAt)}>
          {formatClock(w.startedAt).slice(0, 8)}
        </time>
      </td>
      <td className="py-2 pr-4 font-mono text-mono-sm">
        <time dateTime={w.lastSeenAt} title={formatAbsolute(w.lastSeenAt)}>
          {formatAgo(w.lastSeenAt, now)}
        </time>
      </td>
    </tr>
  );
}

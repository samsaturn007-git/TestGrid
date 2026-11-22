import type { WorkersResponse } from '../api/types.ts';
import { StatusBadge } from './StatusBadge.tsx';

/**
 * Active sessions against the global cap (invariant I5: active + dispatched-unclaimed never
 * exceeds the cap). One segment per session up to 24, a plain bar beyond that.
 */
export function CapacityMeter({ workers }: { workers: WorkersResponse }) {
  const { activeSessions: active, globalMaxSessions: cap, dispatchedUnclaimed: waiting } = workers;
  const label =
    `${active} of ${cap} sessions in use` +
    (waiting > 0 ? `, ${waiting} dispatched and not yet claimed` : '');
  if (cap <= 24) {
    return (
      <div
        role="img"
        aria-label={label}
        className="grid gap-1"
        style={{ gridTemplateColumns: `repeat(${cap}, minmax(0, 1fr))` }}
      >
        {Array.from({ length: cap }, (_, i) => (
          <span
            key={i}
            className={[
              'h-2.5 rounded-tag',
              i < active
                ? 'bg-info'
                : i < active + waiting
                  ? 'border border-dashed border-info'
                  : 'bg-raised',
            ].join(' ')}
          />
        ))}
      </div>
    );
  }
  return (
    <div role="img" aria-label={label} className="h-2.5 overflow-hidden rounded-tag bg-raised">
      <div
        className="h-full bg-info"
        style={{ width: `${Math.min(100, (active / cap) * 100)}%` }}
      />
    </div>
  );
}

const STATES = ['ACTIVE', 'DRAINING', 'LOST'] as const;

/** Workers per state, from the registry (LOST rows are those lost in the last hour). */
export function WorkerStateCounts({ workers }: { workers: WorkersResponse }) {
  return (
    <dl className="flex flex-col gap-1.5">
      {STATES.map((state) => (
        <div key={state} className="flex h-[26px] items-center justify-between">
          <dt>
            <StatusBadge kind="worker" status={state} size="dense" />
          </dt>
          <dd className="font-mono text-mono font-semibold">
            {workers.items.filter((w) => w.state === state).length}
          </dd>
        </div>
      ))}
    </dl>
  );
}

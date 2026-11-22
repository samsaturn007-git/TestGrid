import { EngineMark } from '../../components/Engine.tsx';
import { LoadingState } from '../../components/States.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { formatDuration } from '../../lib/time.ts';
import type { MatrixColumn, MatrixRow } from './model.ts';

/**
 * Cases × engines (PRD-05 FR-2). Each cell is the case's result in its target's latest
 * attempt; until that attempt reports results, the cell shows the target's own state.
 */
export function BrowserMatrix({
  columns,
  rows,
  live,
  casesLoading,
}: {
  columns: MatrixColumn[];
  rows: MatrixRow[];
  live: boolean;
  /** The suite's case list is still loading: rows would be missing, not absent. */
  casesLoading: boolean;
}) {
  return (
    <section aria-labelledby="matrix-h" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="matrix-h" className="text-heading">
          Browser matrix
        </h2>
        <span className="text-body-sm text-text-muted">Latest attempt per target</span>
      </div>
      {casesLoading && rows.length === 0 ? <LoadingState label="Loading cases…" /> : null}
      <div
        hidden={casesLoading && rows.length === 0}
        className="overflow-x-auto rounded-panel border border-hairline bg-surface"
      >
        <table className="w-full border-collapse text-left">
          <caption className="sr-only">Case results by engine, latest attempt per target</caption>
          <thead className="bg-canvas">
            <tr className="border-b border-hairline">
              <th
                scope="col"
                className="py-2.5 pl-4 pr-3 font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted"
              >
                Case
              </th>
              {columns.map(({ target, latest }) => (
                <th key={target.id} scope="col" className="py-2.5 pr-4 align-top font-normal">
                  <span className="flex flex-col gap-1">
                    <span className="flex items-center gap-2 font-mono text-mono font-medium">
                      <EngineMark engine={target.engine} />
                      {target.engine}
                      <StatusBadge
                        kind="target"
                        status={target.status}
                        size="dense"
                        animate={live}
                      />
                    </span>
                    <span className="text-body-sm text-text-muted">
                      {latest
                        ? `attempt ${latest.attempt.attemptNo} of ${target.maxAttempts}`
                        : 'no attempt yet'}
                    </span>
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.caseKey} className="border-b border-hairline-soft last:border-b-0">
                <th scope="row" className="py-2 pl-4 pr-3 text-left font-normal">
                  <span className="flex flex-col">
                    <span className="font-mono text-mono">{row.caseKey}</span>
                    {row.title ? (
                      <span className="text-body-sm text-text-muted">{row.title}</span>
                    ) : null}
                  </span>
                </th>
                {row.cells.map((cell, i) => (
                  <td key={columns[i]?.target.id ?? i} className="py-2 pr-4">
                    {cell.kind === 'case' ? (
                      <span className="flex flex-col gap-0.5">
                        <StatusBadge kind="case" status={cell.result.status} size="dense" />
                        <span className="font-mono text-mono-sm text-text-muted">
                          {cell.result.errorClass ? `${cell.result.errorClass} · ` : ''}
                          {formatDuration(cell.result.durationMs)}
                        </span>
                      </span>
                    ) : (
                      <span className="flex flex-col gap-0.5">
                        <StatusBadge
                          kind="target"
                          status={cell.status}
                          size="dense"
                          animate={live}
                        />
                        <span className="text-body-sm text-text-muted">no case result</span>
                      </span>
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

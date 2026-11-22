import { useId, useRef, useState, type KeyboardEvent } from 'react';
import type { RunResults, RunTarget, TestCase } from '../../api/types.ts';
import { secondaryButton } from '../../components/buttons.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { formatDuration } from '../../lib/time.ts';
import { latestAttempt, sortCases, type CaseSort } from './model.ts';

/**
 * The case results of one target's latest attempt (PRD-05 FR-2): collapsed until asked for,
 * one tab per engine, sortable by status and duration. Default: failures first.
 */
export function CaseTable({
  targets,
  results,
  cases,
}: {
  targets: RunTarget[];
  results: RunResults | undefined;
  cases: TestCase[] | undefined;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState(0);
  const [sort, setSort] = useState<CaseSort>({ by: 'status', direction: 'ascending' });
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const panelId = `${id}-panel`;
  const target = targets[Math.min(selected, targets.length - 1)];
  const titles = new Map((cases ?? []).map((c) => [c.caseKey, c.title]));

  // Tabs pattern: arrow keys move between engines, Home/End jump to the ends.
  function onTabKey(event: KeyboardEvent, index: number) {
    const last = targets.length - 1;
    const next =
      event.key === 'ArrowRight'
        ? index === last
          ? 0
          : index + 1
        : event.key === 'ArrowLeft'
          ? index === 0
            ? last
            : index - 1
          : event.key === 'Home'
            ? 0
            : event.key === 'End'
              ? last
              : null;
    if (next === null) return;
    event.preventDefault();
    setSelected(next);
    tabs.current[next]?.focus();
  }

  function sortBy(by: CaseSort['by']) {
    setSort((s) =>
      s.by === by
        ? { by, direction: s.direction === 'ascending' ? 'descending' : 'ascending' }
        : { by, direction: by === 'status' ? 'ascending' : 'descending' },
    );
  }

  const latest = target ? latestAttempt(results, target.id) : null;
  const rows = latest ? sortCases(latest.caseResults, sort) : [];

  return (
    <section aria-labelledby={`${id}-h`} className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id={`${id}-h`} className="text-heading">
          Cases
        </h2>
        <button
          type="button"
          className={secondaryButton}
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen((v) => !v)}
        >
          {open ? 'Hide case results' : 'Show case results'}
        </button>
      </div>
      <div id={panelId} hidden={!open} className="flex flex-col gap-3">
        {open && target ? (
          <>
            <div role="tablist" aria-label="Engine" className="flex gap-1">
              {targets.map((t, i) => (
                <button
                  key={t.id}
                  ref={(el) => {
                    tabs.current[i] = el;
                  }}
                  type="button"
                  role="tab"
                  id={`${id}-tab-${i}`}
                  aria-selected={i === selected}
                  aria-controls={`${id}-tabpanel`}
                  tabIndex={i === selected ? 0 : -1}
                  onClick={() => setSelected(i)}
                  onKeyDown={(e) => onTabKey(e, i)}
                  className={[
                    'h-[34px] rounded-chip border px-3 font-mono text-mono',
                    i === selected
                      ? 'border-info-rim bg-info-tint text-text'
                      : 'border-transparent text-text-secondary hover:bg-raised',
                  ].join(' ')}
                >
                  {t.engine}
                </button>
              ))}
            </div>
            <div
              role="tabpanel"
              id={`${id}-tabpanel`}
              aria-labelledby={`${id}-tab-${selected}`}
              className="overflow-x-auto rounded-panel border border-hairline bg-surface"
            >
              {!latest ? (
                <p className="px-4 py-3 text-body text-text-muted">
                  {target.engine} has no attempt yet.
                </p>
              ) : rows.length === 0 ? (
                <p className="px-4 py-3 text-body text-text-muted">
                  Attempt {latest.attempt.attemptNo} on {target.engine} has reported no case results
                  {latest.attempt.status === 'RUNNING' || latest.attempt.status === 'CLAIMED'
                    ? ' yet.'
                    : '.'}
                </p>
              ) : (
                <table className="w-full min-w-[860px] border-collapse text-left">
                  <caption className="sr-only">
                    Case results on {target.engine}, attempt {latest.attempt.attemptNo}
                  </caption>
                  <thead className="bg-canvas">
                    <tr className="border-b border-hairline font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted">
                      <th scope="col" className="py-2.5 pl-4 pr-3 font-medium">
                        Case
                      </th>
                      <th scope="col" className="py-2.5 pr-3 font-medium">
                        Title
                      </th>
                      <SortHeader label="Status" by="status" sort={sort} onSort={sortBy} />
                      <SortHeader label="Duration" by="duration" sort={sort} onSort={sortBy} />
                      <th scope="col" className="py-2.5 pr-3 font-medium">
                        Retries
                      </th>
                      <th scope="col" className="py-2.5 pr-3 font-medium">
                        Error class
                      </th>
                      <th scope="col" className="py-2.5 pr-4 font-medium">
                        Message
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r) => (
                      <tr key={r.caseKey} className="border-b border-hairline-soft last:border-b-0">
                        <td className="py-2 pl-4 pr-3 font-mono text-mono">{r.caseKey}</td>
                        <td className="py-2 pr-3 text-body text-text-secondary">
                          {titles.get(r.caseKey) ?? '—'}
                        </td>
                        <td className="py-2 pr-3">
                          <StatusBadge kind="case" status={r.status} size="dense" />
                        </td>
                        <td className="py-2 pr-3 font-mono text-mono-sm">
                          {formatDuration(r.durationMs)}
                        </td>
                        <td className="py-2 pr-3 font-mono text-mono-sm">{r.retryCount}</td>
                        <td className="py-2 pr-3 font-mono text-mono-sm">{r.errorClass ?? '—'}</td>
                        <td className="max-w-[360px] py-2 pr-4 text-body-sm text-text-secondary">
                          {r.errorMessage ?? '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </>
        ) : null}
      </div>
    </section>
  );
}

function SortHeader({
  label,
  by,
  sort,
  onSort,
}: {
  label: string;
  by: CaseSort['by'];
  sort: CaseSort;
  onSort: (by: CaseSort['by']) => void;
}) {
  const active = sort.by === by;
  return (
    <th
      scope="col"
      aria-sort={active ? sort.direction : 'none'}
      className="py-1.5 pr-3 font-medium"
    >
      <button
        type="button"
        onClick={() => onSort(by)}
        className="inline-flex items-center gap-1 rounded-tag px-1 py-1 uppercase hover:bg-raised"
      >
        {label}
        <span aria-hidden="true">
          {active ? (sort.direction === 'ascending' ? '↑' : '↓') : '↕'}
        </span>
      </button>
    </th>
  );
}

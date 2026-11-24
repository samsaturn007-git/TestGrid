import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useFailureGroups, type FailureFilters } from '../api/queries.ts';
import type { Engine, FailureCategory, FailureGroupSummary } from '../api/types.ts';
import { secondaryButton } from '../components/buttons.ts';
import { EngineMark } from '../components/Engine.tsx';
import { EmptyState, ErrorState, LoadingState } from '../components/States.tsx';
import { Page } from '../pages/Page.tsx';
import { CATEGORIES, FailureCategoryBadge } from './FailureCategoryBadge.tsx';

// PRD-07 FR-7: failure groups, most occurrences first, with their likely category and the
// confidence the rule gave it. Filters live in the address so a filtered list can be shared.

const ENGINES: Engine[] = ['chromium', 'firefox', 'webkit'];
const SINCE: Record<string, { label: string; ms: number }> = {
  '24h': { label: 'last 24 hours', ms: 24 * 3_600_000 },
  '7d': { label: 'last 7 days', ms: 7 * 24 * 3_600_000 },
  '30d': { label: 'last 30 days', ms: 30 * 24 * 3_600_000 },
};

function pick<T extends string>(value: string | null, allowed: readonly T[]): T | null {
  return value !== null && (allowed as readonly string[]).includes(value) ? (value as T) : null;
}

function GroupRow({ group }: { group: FailureGroupSummary }) {
  const where = group.caseKeys.length > 0 ? group.caseKeys.join(', ') : 'whole target';
  return (
    <li className="border-b border-hairline-soft last:border-b-0">
      <Link
        to={`/failures/${group.id}`}
        className="grid grid-cols-[minmax(0,190px)_minmax(0,1fr)_64px_90px] items-center gap-3 px-4 py-3 text-text no-underline hover:bg-raised"
      >
        <span className="flex flex-col items-start gap-1.5">
          <FailureCategoryBadge category={group.category} />
          <span className="font-mono text-[11px] text-text-muted">
            confidence {group.confidence.toFixed(2)}
            {group.overridden ? ' · overridden' : ''}
          </span>
        </span>
        <span className="flex min-w-0 flex-col gap-1">
          <span className="truncate font-mono text-mono-sm">
            {group.normalisedMessage.split('\n')[0]}
          </span>
          <span className="truncate text-body-sm text-text-muted">
            {where} · {group.errorClass}
          </span>
        </span>
        <span className="text-right font-mono text-[18px] font-semibold">
          {group.occurrenceCount}
          <span className="sr-only"> occurrences</span>
        </span>
        <span
          className="flex justify-end gap-1"
          aria-label={`Engines: ${group.engines.join(', ')}`}
        >
          {group.engines.map((e) => (
            <EngineMark key={e} engine={e} />
          ))}
        </span>
      </Link>
    </li>
  );
}

export function FailuresPage() {
  const [search, setSearch] = useSearchParams();
  const category = pick<FailureCategory>(search.get('category'), CATEGORIES);
  const engine = pick<Engine>(search.get('engine'), ENGINES);
  const since = pick(search.get('since'), Object.keys(SINCE));
  const filters: FailureFilters = useMemo(
    () => ({ category, engine, sinceMs: since ? (SINCE[since]?.ms ?? null) : null }),
    [category, engine, since],
  );
  const groups = useFailureGroups(filters);
  const items = groups.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = Boolean(category || engine || since);

  const set = (name: string, value: string) => {
    const next = new URLSearchParams(search);
    if (value) next.set(name, value);
    else next.delete(name);
    setSearch(next, { replace: true });
  };

  return (
    <Page
      title="Failures"
      description="The same failure grouped across runs and engines, most occurrences first. Each category is likely, from a transparent rule, never proof."
    >
      <form
        aria-label="Filter failure groups"
        className="flex flex-wrap items-end gap-3"
        onSubmit={(e) => e.preventDefault()}
      >
        <label className="flex flex-col gap-1 text-body-sm text-text-secondary">
          Category
          <select
            value={category ?? ''}
            onChange={(e) => set('category', e.target.value)}
            className="h-8 rounded-chip border border-hairline-strong bg-canvas px-2 font-mono text-mono-sm text-text"
          >
            <option value="">Any</option>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-body-sm text-text-secondary">
          Engine
          <select
            value={engine ?? ''}
            onChange={(e) => set('engine', e.target.value)}
            className="h-8 rounded-chip border border-hairline-strong bg-canvas px-2 font-mono text-mono-sm text-text"
          >
            <option value="">Any</option>
            {ENGINES.map((e) => (
              <option key={e} value={e}>
                {e}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-body-sm text-text-secondary">
          Last seen
          <select
            value={since ?? ''}
            onChange={(e) => set('since', e.target.value)}
            className="h-8 rounded-chip border border-hairline-strong bg-canvas px-2 text-body-sm text-text"
          >
            <option value="">Any time</option>
            {Object.entries(SINCE).map(([key, s]) => (
              <option key={key} value={key}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
      </form>

      {groups.isPending ? <LoadingState label="Loading failure groups…" /> : null}
      {groups.error && !groups.data ? <ErrorState error={groups.error} /> : null}
      {groups.data && items.length === 0 ? (
        <EmptyState
          title={filtered ? 'No group matches these filters' : 'No failures recorded yet'}
        >
          {filtered
            ? 'Clear a filter to see more groups.'
            : 'A group appears when a case fails, times out, flakes, or a whole target fails to run.'}
        </EmptyState>
      ) : null}
      {items.length > 0 ? (
        <div className="overflow-hidden rounded-panel border border-hairline bg-surface">
          <ul aria-label="Failure groups">
            {items.map((g) => (
              <GroupRow key={g.id} group={g} />
            ))}
          </ul>
        </div>
      ) : null}
      {items.length > 0 ? (
        <p className="text-body-sm text-text-muted">
          Showing {items.length} group{items.length === 1 ? '' : 's'}.
        </p>
      ) : null}
      {groups.hasNextPage ? (
        <button
          type="button"
          className={`${secondaryButton} self-start`}
          aria-busy={groups.isFetchingNextPage}
          disabled={groups.isFetchingNextPage}
          onClick={() => void groups.fetchNextPage()}
        >
          {groups.isFetchingNextPage ? 'Loading…' : 'Load more groups'}
        </button>
      ) : null}
    </Page>
  );
}

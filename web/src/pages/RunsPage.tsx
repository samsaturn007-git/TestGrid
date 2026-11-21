import { useId, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useCurrentProject, useRunList, useSuites } from '../api/queries.ts';
import type { RunSummary } from '../api/types.ts';
import { primaryButton, secondaryButton } from '../components/buttons.ts';
import { EmptyState, ErrorState, LoadingState } from '../components/States.tsx';
import { StatusBadge } from '../components/StatusBadge.tsx';
import { formatAbsolute, formatAgo, formatDuration, useNow } from '../lib/time.ts';
import { useNewRun } from '../runs/newRunContext.ts';
import {
  activeFilters,
  branchError,
  filtersFromSearch,
  hasFilters,
  NO_FILTERS,
  RUN_STATUSES,
  searchFromFilters,
  TRIGGERS,
  withoutFilter,
  type RunFilters,
} from '../runs/runFilters.ts';
import { Page } from './Page.tsx';

const selectClass =
  'h-[34px] rounded-chip border border-hairline-strong bg-raised px-2.5 text-body text-text';
const MANY = '__many__';

/** `/runs` (PRD-05 FR-2): filters synced to the URL, keyset "Load more", honest empty states. */
export function RunsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => filtersFromSearch(searchParams), [searchParams]);
  const list = useRunList(filters);
  const runs = useMemo(() => list.data?.pages.flatMap((p) => p.items) ?? [], [list.data]);
  const filtered = hasFilters(filters);

  // Pushing (not replacing) history, so Back undoes a filter change.
  const apply = (next: RunFilters) => setSearchParams(searchFromFilters(next));

  return (
    <Page
      title="Runs"
      description={
        list.data
          ? `${runs.length} loaded · newest first${filtered ? ' · filtered' : ''}`
          : undefined
      }
    >
      <FilterBar filters={filters} onChange={apply} />
      {list.isPending ? <LoadingState label="Loading runs…" /> : null}
      {list.error && !list.data ? (
        <ErrorState
          error={list.error}
          action={
            <div className="flex gap-2">
              <button type="button" className={secondaryButton} onClick={() => void list.refetch()}>
                Try again
              </button>
              {filtered ? (
                <button type="button" className={secondaryButton} onClick={() => apply(NO_FILTERS)}>
                  Clear all filters
                </button>
              ) : null}
            </div>
          }
        />
      ) : null}
      {list.data && runs.length === 0 ? (
        filtered ? (
          <EmptyState title="No runs match these filters">
            <div className="flex flex-col items-start gap-3">
              <span>Remove a filter, or clear them all to see every run.</span>
              <button type="button" className={secondaryButton} onClick={() => apply(NO_FILTERS)}>
                Clear all filters
              </button>
            </div>
          </EmptyState>
        ) : (
          <NoRunsYet />
        )
      ) : null}
      {list.error && list.data && !list.isFetchNextPageError ? (
        // A failed background refresh must not leave old rows looking current (FR-7).
        <p
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-card border border-warning-rim bg-warning-tint px-3 py-2 text-body"
        >
          <span>
            Could not refresh the list. These runs were loaded at{' '}
            <time dateTime={new Date(list.dataUpdatedAt).toISOString()}>
              {formatAbsolute(new Date(list.dataUpdatedAt).toISOString())}
            </time>
            .
          </span>
          <button type="button" className={secondaryButton} onClick={() => void list.refetch()}>
            Try again
          </button>
        </p>
      ) : null}
      {runs.length > 0 ? (
        <RunTable
          runs={runs}
          hasMore={list.hasNextPage}
          loadingMore={list.isFetchingNextPage}
          loadMoreError={list.isFetchNextPageError ? list.error : null}
          onLoadMore={() => list.fetchNextPage()}
        />
      ) : null}
    </Page>
  );
}

function NoRunsYet() {
  const { openNewRun } = useNewRun();
  return (
    <EmptyState title="No runs yet">
      <div className="flex flex-col items-start gap-3">
        <span>Runs appear here as soon as one is created. Start one from a registered suite.</span>
        <button
          type="button"
          className={primaryButton}
          aria-haspopup="dialog"
          onClick={() => openNewRun()}
        >
          Start a run
        </button>
      </div>
    </EmptyState>
  );
}

function FilterBar({
  filters,
  onChange,
}: {
  filters: RunFilters;
  onChange: (next: RunFilters) => void;
}) {
  const id = useId();
  const { project } = useCurrentProject();
  const suites = useSuites(project?.id);
  const suiteKeys = useMemo(() => {
    const keys = (suites.data?.items ?? []).map((s) => s.suiteKey);
    // A suite from a shared link stays visible even if the manifest no longer has it.
    if (filters.suite && !keys.includes(filters.suite)) keys.push(filters.suite);
    return keys;
  }, [suites.data, filters.suite]);

  // The text box follows the URL (Back/forward, a removed chip). Adjusting state during render
  // when its source changes is React's pattern for this; an effect would render twice.
  const [draft, setDraft] = useState({
    source: filters.branch,
    text: filters.branch ?? '',
    problem: null as string | null,
  });
  if (draft.source !== filters.branch) {
    setDraft({ source: filters.branch, text: filters.branch ?? '', problem: null });
  }
  const branch = draft.text;
  const branchProblem = draft.problem;

  function submitBranch(event: FormEvent) {
    event.preventDefault();
    const problem = branchError(branch);
    setDraft((d) => ({ ...d, problem }));
    if (problem) return;
    const value = branch.trim();
    onChange({ ...filters, branch: value === '' ? null : value });
  }

  const active = activeFilters(filters);
  const branchErrorId = `${id}-branch-error`;

  return (
    <div className="flex flex-col gap-3">
      <form
        role="search"
        aria-label="Filter runs"
        onSubmit={submitBranch}
        className="flex flex-wrap items-end gap-3"
      >
        <MultiSelect
          id={`${id}-status`}
          label="Status"
          anyLabel="Any status"
          options={RUN_STATUSES}
          values={filters.statuses}
          onChange={(statuses) => onChange({ ...filters, statuses })}
        />
        <div className="flex flex-col gap-1">
          <label htmlFor={`${id}-suite`} className="text-body-sm text-text-muted">
            Suite
          </label>
          <select
            id={`${id}-suite`}
            className={selectClass}
            value={filters.suite ?? ''}
            onChange={(e) => onChange({ ...filters, suite: e.target.value || null })}
          >
            <option value="">Any suite</option>
            {suiteKeys.map((key) => (
              <option key={key} value={key}>
                {key}
              </option>
            ))}
          </select>
        </div>
        <MultiSelect
          id={`${id}-trigger`}
          label="Trigger"
          anyLabel="Any trigger"
          options={TRIGGERS}
          values={filters.triggers}
          onChange={(triggers) => onChange({ ...filters, triggers })}
        />
        <div className="flex flex-col gap-1">
          <label htmlFor={`${id}-branch`} className="text-body-sm text-text-muted">
            Branch
          </label>
          <div className="flex gap-2">
            <input
              id={`${id}-branch`}
              type="text"
              autoComplete="off"
              spellCheck={false}
              placeholder="Exact branch name"
              value={branch}
              onChange={(e) => {
                const text = e.target.value;
                setDraft((d) => ({ ...d, text }));
              }}
              aria-invalid={branchProblem ? true : false}
              {...(branchProblem ? { 'aria-describedby': branchErrorId } : {})}
              className={`${selectClass} w-[220px] bg-surface font-mono aria-invalid:border-danger`}
            />
            <button type="submit" className={secondaryButton}>
              Apply
            </button>
          </div>
        </div>
      </form>
      {branchProblem ? (
        <p id={branchErrorId} className="text-body-sm text-danger">
          {branchProblem}
        </p>
      ) : null}

      {active.length > 0 ? (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-body-sm text-text-muted">Active filters</span>
          <ul className="flex flex-wrap gap-2" aria-label="Active filters">
            {active.map(({ key, value }) => (
              <li
                key={`${key}:${value}`}
                className="inline-flex h-7 items-center gap-1.5 rounded-chip border border-info-rim bg-info-tint pl-2 pr-1 text-body-sm"
              >
                <span className="text-text-muted">{key}</span>
                <span className="font-mono">{value}</span>
                <button
                  type="button"
                  aria-label={`Remove filter ${key}: ${value}`}
                  className="inline-flex size-5 items-center justify-center rounded-tag text-text-secondary hover:bg-raised"
                  onClick={() => onChange(withoutFilter(filters, key, value))}
                >
                  <svg
                    width="10"
                    height="10"
                    viewBox="0 0 16 16"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    aria-hidden="true"
                  >
                    <path d="M4 4l8 8M12 4l-8 8" />
                  </svg>
                </button>
              </li>
            ))}
          </ul>
          <button
            type="button"
            className="text-body-sm text-info underline-offset-2 hover:underline"
            onClick={() => onChange(NO_FILTERS)}
          >
            Clear all
          </button>
          <CopyLink />
        </div>
      ) : null}
    </div>
  );
}

/**
 * One choice from the menu replaces the filter; a link may carry several values (the API ORs
 * them), shown as one "N selected" option until a single value is picked.
 */
function MultiSelect({
  id,
  label,
  anyLabel,
  options,
  values,
  onChange,
}: {
  id: string;
  label: string;
  anyLabel: string;
  options: readonly string[];
  values: string[];
  onChange: (values: string[]) => void;
}) {
  const extra = values.filter((v) => !options.includes(v));
  const value = values.length === 0 ? '' : values.length === 1 ? (values[0] ?? '') : MANY;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-body-sm text-text-muted">
        {label}
      </label>
      <select
        id={id}
        className={selectClass}
        value={value}
        onChange={(e) => {
          const next = e.target.value;
          if (next !== MANY) onChange(next === '' ? [] : [next]);
        }}
      >
        <option value="">{anyLabel}</option>
        {values.length > 1 ? <option value={MANY}>{values.join(', ')}</option> : null}
        {[...options, ...extra].map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </div>
  );
}

function CopyLink() {
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null);
  return (
    <span className="ml-auto inline-flex items-center gap-2">
      <button
        type="button"
        className={secondaryButton}
        onClick={() => {
          navigator.clipboard.writeText(window.location.href).then(
            () => setCopied('yes'),
            () => setCopied('no'),
          );
        }}
      >
        Copy link
      </button>
      <span role="status" className="text-body-sm text-text-muted">
        {copied === 'yes'
          ? 'Link copied'
          : copied === 'no'
            ? 'Copy failed: use the address bar'
            : ''}
      </span>
    </span>
  );
}

function RunTable({
  runs,
  hasMore,
  loadingMore,
  loadMoreError,
  onLoadMore,
}: {
  runs: RunSummary[];
  hasMore: boolean;
  loadingMore: boolean;
  loadMoreError: unknown;
  onLoadMore: () => Promise<unknown>;
}) {
  const now = useNow();
  const tableRef = useRef<HTMLTableElement>(null);

  async function loadMore() {
    const before = runs.length;
    await onLoadMore();
    // Keyboard users continue at the first run that just arrived, not at the top of the page.
    requestAnimationFrame(() => {
      tableRef.current
        ?.querySelectorAll<HTMLAnchorElement>('tbody a[data-run-link]')
        [before]?.focus();
    });
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="overflow-x-auto rounded-panel border border-hairline bg-surface">
        <table ref={tableRef} className="w-full min-w-[960px] border-collapse text-left">
          <caption className="sr-only">Runs, newest first</caption>
          <thead className="bg-canvas">
            <tr className="border-b border-hairline font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted">
              {[
                'Status',
                'Run',
                'Suite',
                'Targets',
                'Trigger',
                'Fixture',
                'Branch · commit',
                'Queued',
                'Duration',
              ].map((h, i) => (
                <th
                  key={h}
                  scope="col"
                  className={`py-2.5 font-medium ${i === 0 ? 'pl-4' : ''} pr-3`}
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {runs.map((run) => (
              <RunRow key={run.id} run={run} now={now} />
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <span aria-live="polite" className="text-body-sm text-text-muted">
          {hasMore
            ? `${runs.length} runs loaded. There are older runs.`
            : `All ${runs.length} ${runs.length === 1 ? 'run' : 'runs'} loaded.`}
        </span>
        {hasMore ? (
          <button
            type="button"
            className={secondaryButton}
            aria-busy={loadingMore}
            aria-disabled={loadingMore}
            onClick={() => {
              if (!loadingMore) void loadMore();
            }}
          >
            {loadingMore ? 'Loading…' : 'Load more'}
          </button>
        ) : null}
      </div>
      {loadMoreError ? <ErrorState error={loadMoreError} /> : null}
    </div>
  );
}

function RunRow({ run, now }: { run: RunSummary; now: number }) {
  const shortId = run.id.slice(0, 8);
  const duration =
    run.startedAt === null
      ? null
      : run.finishedAt !== null
        ? formatDuration(Date.parse(run.finishedAt) - Date.parse(run.startedAt))
        : `${formatDuration(now - Date.parse(run.startedAt))} so far`;

  return (
    <tr className="h-11 border-b border-hairline-soft last:border-b-0 hover:bg-raised">
      <td className="pl-4 pr-3">
        <StatusBadge kind="run" status={run.status} size="dense" />
      </td>
      <td className="pr-3">
        <Link to={`/runs/${run.id}`} data-run-link className="font-mono text-mono" title={run.id}>
          {shortId}
        </Link>
      </td>
      <td className="pr-3">
        <span className="font-mono text-mono">{run.suiteKey}</span>
      </td>
      <td className="pr-3 font-mono text-mono">{run.targetCount}</td>
      <td className="pr-3 font-mono text-mono-sm">{run.trigger}</td>
      <td className="pr-3 font-mono text-mono-sm">
        {run.fixture ? `${run.fixture.mode} · ${run.fixture.seed}` : <NoValue label="no fixture" />}
      </td>
      <td className="pr-3">
        {run.source?.branch || run.source?.commitSha ? (
          <span className="flex flex-col font-mono text-mono-sm leading-4">
            <span>{run.source.branch ?? <NoValue label="no branch" />}</span>
            {run.source.commitSha ? (
              <span className="text-text-muted" title={run.source.commitSha}>
                {run.source.commitSha.slice(0, 7)}
              </span>
            ) : null}
          </span>
        ) : (
          <NoValue label="no source" />
        )}
      </td>
      <td className="pr-3 text-body-sm whitespace-nowrap">
        <time dateTime={run.queuedAt} title={formatAbsolute(run.queuedAt)}>
          {formatAgo(run.queuedAt, now)}
        </time>
      </td>
      <td className="pr-4 font-mono text-mono-sm whitespace-nowrap">
        {duration ?? <NoValue label="not started" />}
      </td>
    </tr>
  );
}

/** A dash for sighted users that still says what is missing to a screen reader. */
function NoValue({ label }: { label: string }) {
  return (
    <span className="text-text-muted">
      <span aria-hidden="true">—</span>
      <span className="sr-only">{label}</span>
    </span>
  );
}

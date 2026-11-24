import { useId, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../api/client.ts';
import {
  useCreateQuarantine,
  useCurrentProject,
  useQuarantines,
  useRevokeQuarantine,
} from '../api/queries.ts';
import type {
  CreateQuarantineRequest,
  Engine,
  Quarantine,
  QuarantineList,
  QuarantineState,
  QuarantineSuggestion,
} from '../api/types.ts';
import { primaryButton, secondaryButton } from '../components/buttons.ts';
import { EmptyState, ErrorState, LoadingState } from '../components/States.tsx';
import { formatAbsolute, useNow } from '../lib/time.ts';
import { Page } from '../pages/Page.tsx';

const DAY_MS = 86_400_000;
const DAYS = [1, 3, 7, 14, 30] as const;
const ENGINES: Engine[] = ['chromium', 'firefox', 'webkit'];
const FILTERS: (QuarantineState | 'ALL')[] = ['ACTIVE', 'EXPIRED', 'REVOKED', 'ALL'];
/** Under two days left, the countdown turns amber. */
const SOON_MS = 2 * DAY_MS;

/**
 * Server clock minus browser clock. The API sends `serverNow` with the list; adding this offset
 * keeps countdowns and new expiries on the database's clock, which is the one that decides
 * (contracts §1), even when the laptop's clock is off.
 */
function serverOffset(list: QuarantineList | undefined, receivedAt: number): number {
  return list ? Date.parse(list.serverNow) - receivedAt : 0;
}

/** "6 d 23 h", "5 h 12 min", "4 min". */
export function formatRemaining(ms: number): string {
  const minutes = Math.max(0, Math.floor(ms / 60_000));
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ${minutes % 60} min`;
  return `${Math.floor(hours / 24)} d ${hours % 24} h`;
}

/**
 * An expiry `days` ahead of the server's clock. A 30-day one is kept a minute short, so the time
 * the request takes can't push it past the 30-day limit the API enforces.
 */
function expiryIn(days: number, serverNow: number): string {
  return new Date(serverNow + days * DAY_MS - (days === 30 ? 60_000 : 0)).toISOString();
}

/**
 * `/quarantines` (PRD-08 FR-3, FR-7). A quarantined failure is still reported as QUARANTINED_FAIL;
 * it just doesn't block. Every quarantine has a reason and an expiry of at most 30 days, the
 * policy caps how many are active, and every change is audited.
 */
export function QuarantinesPage() {
  const { project, isPending, error } = useCurrentProject();
  return (
    <Page
      title="Quarantines"
      description="A quarantined failure still shows in every report as QUARANTINED_FAIL, but it doesn't block the gate. Each one has a reason and expires within 30 days, and every change is audited."
    >
      {isPending ? <LoadingState label="Loading projects…" /> : null}
      {error ? <ErrorState error={error} /> : null}
      {project ? <Quarantines projectId={project.id} /> : null}
    </Page>
  );
}

function Quarantines({ projectId }: { projectId: string }) {
  const list = useQuarantines(projectId);
  // Rows age with a 30 s display clock; a new expiry reads the clock when it is submitted.
  const offset = serverOffset(list.data, list.dataUpdatedAt);
  const serverNow = useNow(30_000) + offset;
  const [filter, setFilter] = useState<QuarantineState | 'ALL'>('ACTIVE');
  const filterId = useId();
  if (list.isPending) return <LoadingState label="Loading quarantines…" />;
  if (list.error) return <ErrorState error={list.error} />;
  const data = list.data;
  const shown = data.items.filter((q) => filter === 'ALL' || q.state === filter);
  const full = data.activeCount >= data.maxActive;

  return (
    <>
      <p className="text-body text-text-secondary">
        <span className="font-mono">
          {data.activeCount} of {data.maxActive}
        </span>{' '}
        active quarantines allowed by the policy · auto mode{' '}
        <span className="font-mono">{data.autoMode}</span> ·{' '}
        <Link to={`/projects/${projectId}/policy`} className="underline">
          Quality policy
        </Link>
      </p>
      {data.suggestions.length > 0 ? (
        <section aria-labelledby="suggestions" className="flex flex-col gap-3">
          <h2 id="suggestions" className="text-body font-semibold">
            Suggested: cases labelled FLAKY
          </h2>
          {data.suggestions.map((s) => (
            <SuggestionCard
              key={`${s.suiteKey}:${s.caseKey}:${s.engine}`}
              suggestion={s}
              projectId={projectId}
              clockOffset={offset}
              full={full}
            />
          ))}
        </section>
      ) : null}
      <CreateForm projectId={projectId} clockOffset={offset} full={full} />
      <section
        aria-labelledby="quarantine-list"
        className="overflow-hidden rounded-card border border-hairline bg-surface"
      >
        <div className="flex flex-wrap items-center gap-3 border-b border-hairline px-4 py-3">
          <h2 id="quarantine-list" className="text-body font-semibold">
            Quarantines
          </h2>
          <label htmlFor={filterId} className="ml-auto text-body-sm text-text-secondary">
            Show
          </label>
          <select
            id={filterId}
            value={filter}
            onChange={(e) => setFilter(e.target.value as QuarantineState | 'ALL')}
            className="h-[30px] rounded-chip border border-hairline-strong bg-canvas px-2 font-mono text-mono-sm text-text"
          >
            {FILTERS.map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </select>
        </div>
        {shown.length === 0 ? (
          <div className="p-4">
            <EmptyState title={filter === 'ACTIVE' ? 'No active quarantines' : 'None'}>
              {filter === 'ACTIVE'
                ? 'Every failure counts toward the gate.'
                : 'Nothing in this state yet.'}
            </EmptyState>
          </div>
        ) : (
          <ul aria-label="Quarantines, newest first">
            {shown.map((q) => (
              <QuarantineRow
                key={q.id}
                quarantine={q}
                projectId={projectId}
                serverNow={serverNow}
              />
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

function problemText(error: unknown): string | null {
  if (!error) return null;
  if (error instanceof ApiError && error.problem) {
    return (
      (error.problem.detail ?? error.problem.title) +
      (error.problem.errors?.map((e) => ` ${e.field}: ${e.message}.`).join('') ?? '')
    );
  }
  return 'Could not save the quarantine. Try again.';
}

const field =
  'h-8 rounded-chip border border-hairline-strong bg-canvas px-2 text-body-sm text-text';

function DaysSelect({
  id,
  value,
  onChange,
}: {
  id: string;
  value: number;
  onChange: (days: number) => void;
}) {
  return (
    <select
      id={id}
      value={value}
      onChange={(e) => onChange(Number(e.target.value))}
      className={field}
    >
      {DAYS.map((d) => (
        <option key={d} value={d}>
          {d === 30 ? '30 days (maximum)' : d === 1 ? '1 day' : `${d} days`}
        </option>
      ))}
    </select>
  );
}

function SuggestionCard({
  suggestion,
  projectId,
  clockOffset,
  full,
}: {
  suggestion: QuarantineSuggestion;
  projectId: string;
  /** Server clock minus browser clock, from the last list. */
  clockOffset: number;
  full: boolean;
}) {
  const create = useCreateQuarantine(projectId);
  const [reason, setReason] = useState('');
  const [days, setDays] = useState(7);
  const reasonId = useId();
  const daysId = useId();
  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate({
      caseKey: suggestion.caseKey,
      engine: suggestion.engine,
      reason,
      expiresAt: expiryIn(days, Date.now() + clockOffset),
      source: 'SUGGESTED',
    });
  };
  return (
    <form
      onSubmit={submit}
      aria-label={`Quarantine ${suggestion.caseKey} on ${suggestion.engine}`}
      className="flex flex-col gap-2.5 rounded-card border border-flaky-rim bg-flaky-tint p-4"
    >
      <p className="flex flex-wrap items-center gap-2 text-body">
        <span className="rounded-[4px] border border-flaky-rim px-[5px] py-[3px] font-mono text-[11px] font-medium leading-none text-flaky">
          FLAKY
        </span>
        <span>
          <span className="font-mono">{suggestion.caseKey}</span> on {suggestion.engine}.
          Quarantine?
        </span>
        <span className="text-body-sm text-text-secondary">
          {suggestion.reason} ({suggestion.suiteKey})
        </span>
      </p>
      <label htmlFor={reasonId} className="text-body-sm text-text-secondary">
        Reason <span className="text-text-muted">required</span>
      </label>
      <input
        id={reasonId}
        required
        maxLength={500}
        value={reason}
        placeholder="Why it is safe to ignore for now"
        onChange={(e) => setReason(e.target.value)}
        className={field}
      />
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={daysId} className="text-body-sm text-text-secondary">
          Expires in
        </label>
        <DaysSelect id={daysId} value={days} onChange={setDays} />
        <button
          type="submit"
          className={`${primaryButton} ml-auto`}
          disabled={create.isPending || reason.trim() === '' || full}
          aria-busy={create.isPending}
        >
          Quarantine
        </button>
      </div>
      {full ? (
        <p className="text-body-sm text-text-secondary">The policy's cap is reached.</p>
      ) : null}
      {problemText(create.error) ? (
        <p role="alert" className="text-body-sm text-danger">
          {problemText(create.error)}
        </p>
      ) : null}
    </form>
  );
}

function CreateForm({
  projectId,
  clockOffset,
  full,
}: {
  projectId: string;
  /** Server clock minus browser clock, from the last list. */
  clockOffset: number;
  full: boolean;
}) {
  const create = useCreateQuarantine(projectId);
  const [caseKey, setCaseKey] = useState('');
  const [engine, setEngine] = useState<Engine | ''>('');
  const [reason, setReason] = useState('');
  const [days, setDays] = useState(7);
  const [created, setCreated] = useState<string | null>(null);
  const ids = { caseKey: useId(), engine: useId(), reason: useId(), days: useId() };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setCreated(null);
    const body: CreateQuarantineRequest = {
      caseKey: caseKey.trim(),
      reason,
      expiresAt: expiryIn(days, Date.now() + clockOffset),
      source: 'MANUAL',
      ...(engine ? { engine } : {}),
    };
    create.mutate(body, {
      onSuccess: (q) => {
        setCreated(`${q.caseKey} quarantined until ${formatAbsolute(q.expiresAt)}.`);
        setCaseKey('');
        setReason('');
      },
    });
  };
  return (
    <form
      onSubmit={submit}
      aria-labelledby="create-quarantine"
      className="flex flex-col gap-2.5 rounded-card border border-hairline bg-surface p-4"
    >
      <h2 id="create-quarantine" className="text-body font-semibold">
        Quarantine a case
      </h2>
      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-[minmax(0,1fr)_160px_180px]">
        <span className="flex flex-col gap-1">
          <label htmlFor={ids.caseKey} className="text-body-sm text-text-secondary">
            Case key
          </label>
          <input
            id={ids.caseKey}
            required
            maxLength={64}
            pattern="[a-z0-9]+(-[a-z0-9]+)*"
            placeholder="checkout-happy-path"
            value={caseKey}
            onChange={(e) => setCaseKey(e.target.value)}
            className={`${field} font-mono`}
          />
        </span>
        <span className="flex flex-col gap-1">
          <label htmlFor={ids.engine} className="text-body-sm text-text-secondary">
            Engine
          </label>
          <select
            id={ids.engine}
            value={engine}
            onChange={(e) => setEngine(e.target.value as Engine | '')}
            className={field}
          >
            <option value="">every engine</option>
            {ENGINES.map((e) => (
              <option key={e} value={e}>
                {e}
              </option>
            ))}
          </select>
        </span>
        <span className="flex flex-col gap-1">
          <label htmlFor={ids.days} className="text-body-sm text-text-secondary">
            Expires in
          </label>
          <DaysSelect id={ids.days} value={days} onChange={setDays} />
        </span>
      </div>
      <label htmlFor={ids.reason} className="text-body-sm text-text-secondary">
        Reason <span className="text-text-muted">required, kept in the audit log</span>
      </label>
      <input
        id={ids.reason}
        required
        maxLength={500}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        className={field}
      />
      {problemText(create.error) ? (
        <p role="alert" className="text-body-sm text-danger">
          {problemText(create.error)}
        </p>
      ) : null}
      {created ? (
        <p role="status" className="text-body-sm text-success">
          {created}
        </p>
      ) : null}
      {full ? (
        <p className="text-body-sm text-text-secondary">
          The policy's cap is reached: revoke one or wait for one to expire.
        </p>
      ) : null}
      <button
        type="submit"
        className={`${primaryButton} self-start`}
        disabled={create.isPending || reason.trim() === '' || caseKey.trim() === '' || full}
        aria-busy={create.isPending}
      >
        Quarantine
      </button>
    </form>
  );
}

function QuarantineRow({
  quarantine: q,
  projectId,
  serverNow,
}: {
  quarantine: Quarantine;
  projectId: string;
  serverNow: number;
}) {
  const revoke = useRevokeQuarantine(projectId);
  const total = Date.parse(q.expiresAt) - Date.parse(q.createdAt);
  const left = Date.parse(q.expiresAt) - serverNow;
  // The server's state decides; the countdown only ages it on screen between refreshes.
  const active = q.state === 'ACTIVE' && left > 0;
  const soon = active && left < SOON_MS;
  const fraction = active && total > 0 ? Math.min(1, Math.max(0, left / total)) : 0;
  return (
    <li className="flex flex-col gap-2 border-b border-hairline-soft px-4 py-3 last:border-b-0">
      <div className="flex flex-wrap items-center gap-2.5">
        <span className="font-mono text-[13px] font-medium">{q.caseKey}</span>
        <span className="font-mono text-[11px] text-text-secondary">
          {q.engine ?? 'every engine'}
        </span>
        <span className="rounded-[4px] border border-hairline-strong px-[5px] py-[3px] font-mono text-[10px] leading-none text-text-secondary">
          {q.source}
        </span>
        {q.state !== 'ACTIVE' ? (
          <span className="font-mono text-[11px] text-text-muted">{q.state}</span>
        ) : null}
        {q.state === 'ACTIVE' ? (
          <button
            type="button"
            className={`${secondaryButton} ml-auto h-7 px-2.5`}
            onClick={() => revoke.mutate(q.id)}
            disabled={revoke.isPending}
            aria-busy={revoke.isPending}
            aria-label={`Revoke the quarantine of ${q.caseKey} on ${q.engine ?? 'every engine'}`}
          >
            Revoke
          </button>
        ) : null}
      </div>
      <p className="text-body-sm text-text-secondary">{q.reason}</p>
      <div className="flex items-center gap-2.5 font-mono text-[11px] text-text-secondary">
        <span>
          by {q.createdBy} · <time dateTime={q.createdAt}>{formatAbsolute(q.createdAt)}</time>
        </span>
        <span
          className="h-1 flex-1 overflow-hidden rounded-[2px] bg-hairline"
          role="meter"
          aria-label="Time left"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={Math.round(fraction * 100)}
        >
          <span
            className={`block h-1 ${soon ? 'bg-warning' : 'bg-flaky'}`}
            style={{ width: `${fraction * 100}%` }}
          />
        </span>
        <span className={soon ? 'text-warning' : ''}>
          {q.state === 'REVOKED' && q.revokedAt ? (
            <>
              revoked <time dateTime={q.revokedAt}>{formatAbsolute(q.revokedAt)}</time>
            </>
          ) : active ? (
            `expires in ${formatRemaining(left)}`
          ) : (
            <>
              expired <time dateTime={q.expiresAt}>{formatAbsolute(q.expiresAt)}</time>
            </>
          )}
        </span>
      </div>
      {revoke.error ? (
        <p role="alert" className="text-body-sm text-danger">
          {problemText(revoke.error)}
        </p>
      ) : null}
    </li>
  );
}

import { useId, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '../api/client.ts';
import { freshArtifactLink, useFailureGroup, useOverrideFailureGroup } from '../api/queries.ts';
import type {
  ArtifactView,
  FailureCategory,
  FailureGroupView,
  FailureOccurrence,
} from '../api/types.ts';
import { primaryButton, secondaryButton } from '../components/buttons.ts';
import { ErrorState, LoadingState } from '../components/States.tsx';
import { formatAbsolute } from '../lib/time.ts';
import { Page } from '../pages/Page.tsx';
import { CATEGORIES, FailureCategoryBadge } from './FailureCategoryBadge.tsx';

// PRD-07 FR-7: one failure group. The rule's explanation sits next to the label (never a bare
// label), the occurrences link to their runs, cases and artifacts, and a human can override the
// category with a reason that lands in the audit log (FR-3).

const heading = 'font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted';

const ARTIFACT_LABEL: Record<ArtifactView['kind'], string> = {
  SCREENSHOT: 'screenshot',
  TRACE: 'trace',
  LOG: 'log',
};

function ArtifactButton({ artifact }: { artifact: ArtifactView }) {
  const [state, setState] = useState<'idle' | 'busy' | 'failed'>('idle');
  const label = ARTIFACT_LABEL[artifact.kind];
  if (artifact.state !== 'AVAILABLE') {
    return (
      <span className="font-mono text-[11px] text-text-muted">
        {label} {artifact.state}
      </span>
    );
  }
  return (
    <button
      type="button"
      className="font-mono text-[11px] text-info underline-offset-2 hover:underline aria-busy:opacity-70"
      aria-busy={state === 'busy'}
      onClick={() => {
        setState('busy');
        freshArtifactLink(artifact.id).then(
          (link) => {
            setState('idle');
            // Content-Disposition: attachment, so the page stays and the file downloads.
            window.location.assign(link.url);
          },
          () => setState('failed'),
        );
      }}
    >
      {state === 'failed' ? `${label}: link failed, retry` : `download ${label}`}
    </button>
  );
}

function Evidence({ group }: { group: FailureGroupView }) {
  const e = group.evidence;
  const c = e.confidence;
  return (
    <section aria-labelledby="why" className="flex flex-col gap-2">
      <h2 id="why" className={heading}>
        Matched rule · why this label
      </h2>
      <div className="flex flex-col gap-2 rounded-card border border-info-rim bg-info-tint px-3 py-2.5 text-body-sm">
        <p>
          <span className="font-mono">
            {e.ruleId} {e.ruleName}
          </span>{' '}
          → <span className="font-mono">{e.category}</span>. {e.explanation}
        </p>
        <ol aria-label="Rules tried, in order" className="flex flex-col gap-1 text-text-secondary">
          {e.rulesTried.map((r) => (
            <li key={r.ruleId} className="flex gap-2">
              <span aria-hidden="true" className={r.matched ? 'text-success' : 'text-text-muted'}>
                {r.matched ? '✓' : '–'}
              </span>
              <span>
                <span className="font-mono">{r.ruleId}</span>{' '}
                {r.matched ? 'matched' : 'did not match'}: {r.reason}
              </span>
            </li>
          ))}
        </ol>
        <p className="font-mono text-mono-sm text-text-secondary">
          confidence {c.base.toFixed(2)} base + {c.occurrenceBonus.toFixed(2)} for occurrences −{' '}
          {c.disagreementPenalty.toFixed(2)} disagreement = {c.value.toFixed(2)} (agreement{' '}
          {c.agreement.toFixed(2)}, cap {c.cap.toFixed(2)})
        </p>
      </div>
    </section>
  );
}

function OccurrenceRow({ o }: { o: FailureOccurrence }) {
  return (
    <tr className="border-b border-hairline-soft align-top last:border-b-0">
      <td className="px-3 py-2 font-mono">
        <Link to={`/runs/${o.runId}`}>{o.runId.slice(0, 8)}</Link>
      </td>
      <td className="px-3 py-2 font-mono">{o.engine}</td>
      <td className="px-3 py-2 font-mono">
        {o.caseKey ? (
          <Link
            to={`/cases/${encodeURIComponent(o.caseKey)}?suiteKey=${encodeURIComponent(o.suiteKey)}`}
          >
            {o.caseKey}
          </Link>
        ) : (
          <span className="text-text-muted">whole target</span>
        )}
      </td>
      <td className="px-3 py-2 font-mono">
        {o.status}
        {o.attemptNo > 1 ? <span className="text-text-muted"> · attempt {o.attemptNo}</span> : null}
      </td>
      <td className="px-3 py-2 text-text-secondary">{formatAbsolute(o.occurredAt)}</td>
      <td className="px-3 py-2">
        <span className="flex flex-col items-start gap-1">
          {o.artifacts.length === 0 ? (
            <span className="text-body-sm text-text-muted">none kept</span>
          ) : (
            o.artifacts.map((a) => <ArtifactButton key={a.id} artifact={a} />)
          )}
        </span>
      </td>
      <td className="max-w-[360px] px-3 py-2">
        {o.errorMessage ? (
          <details>
            <summary className="cursor-pointer text-body-sm text-text-secondary">message</summary>
            <pre className="mt-1 whitespace-pre-wrap font-mono text-mono-sm">{o.errorMessage}</pre>
          </details>
        ) : (
          <span className="text-body-sm text-text-muted">none</span>
        )}
      </td>
    </tr>
  );
}

function OverrideForm({ group }: { group: FailureGroupView }) {
  const override = useOverrideFailureGroup(group.id);
  const [category, setCategory] = useState<FailureCategory>(
    group.override?.category ?? (group.ruleCategory === 'UNKNOWN' ? 'TEST_BROKEN' : 'UNKNOWN'),
  );
  const [reason, setReason] = useState('');
  const [saved, setSaved] = useState(false);
  const categoryId = useId();
  const reasonId = useId();
  const problem = override.error instanceof ApiError ? override.error.problem : null;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setSaved(false);
    override.mutate(
      { category, reason },
      {
        onSuccess: () => {
          setSaved(true);
          setReason('');
        },
      },
    );
  };

  return (
    <form
      aria-labelledby="override"
      onSubmit={submit}
      className="flex flex-col gap-2.5 rounded-card border border-hairline bg-surface p-4"
    >
      <h2 id="override" className="text-body font-semibold">
        Change category
      </h2>
      <label htmlFor={categoryId} className="text-body-sm text-text-secondary">
        New category
      </label>
      <select
        id={categoryId}
        value={category}
        onChange={(e) => setCategory(e.target.value as FailureCategory)}
        className="h-8 rounded-chip border border-hairline-strong bg-canvas px-2 font-mono text-mono-sm text-text"
      >
        {CATEGORIES.map((c) => (
          <option key={c} value={c}>
            {c}
          </option>
        ))}
      </select>
      <label htmlFor={reasonId} className="text-body-sm text-text-secondary">
        Reason <span className="text-text-muted">required, kept in the audit log</span>
      </label>
      <textarea
        id={reasonId}
        required
        maxLength={500}
        rows={3}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        className="resize-none rounded-chip border border-hairline-strong bg-canvas p-2 text-body-sm text-text"
      />
      <span className="self-end font-mono text-[11px] text-text-muted">{reason.length} / 500</span>
      {problem ? (
        <p role="alert" className="text-body-sm text-danger">
          {problem.detail}
          {problem.errors?.map((e) => ` ${e.field}: ${e.message}.`).join('')}
        </p>
      ) : null}
      {saved ? (
        <p role="status" className="text-body-sm text-success">
          Saved. The change is in the audit history below.
        </p>
      ) : null}
      <button
        type="submit"
        className={`${primaryButton} self-start`}
        disabled={override.isPending || reason.trim() === ''}
        aria-busy={override.isPending}
      >
        Save override
      </button>
    </form>
  );
}

function Group({ group }: { group: FailureGroupView }) {
  const f = group.evidence.facts;
  return (
    <>
      <section aria-label="Summary" className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-3">
          <FailureCategoryBadge category={group.category} />
          <span className="font-mono text-mono">{group.confidence.toFixed(2)}</span>
          <span className="text-body-sm text-text-muted">
            {f.occurrences} occurrences · {f.runs} run{f.runs === 1 ? '' : 's'} · {f.engines} engine
            {f.engines === 1 ? '' : 's'} · {group.errorClass}
          </span>
        </div>
        <p className="text-body-sm text-text-secondary">
          {group.override ? (
            <>
              Overridden to <span className="font-mono">{group.override.category}</span> by{' '}
              {group.override.by} on {formatAbsolute(group.override.at)}: “{group.override.reason}”.
              The rule says <span className="font-mono">{group.ruleCategory}</span>.
            </>
          ) : (
            <>
              Likely <span className="font-mono">{group.category}</span>, by rule{' '}
              <span className="font-mono">{group.ruleId}</span>. A label from evidence, not proof of
              the cause.
            </>
          )}
        </p>
        <p className="font-mono text-[11px] text-text-muted">
          {group.algoVersion} · {group.fingerprint} · first seen {formatAbsolute(group.firstSeenAt)}{' '}
          · last seen {formatAbsolute(group.lastSeenAt)}
        </p>
      </section>
      <section aria-labelledby="normalised" className="flex flex-col gap-1.5">
        <h2 id="normalised" className={heading}>
          Normalised message
        </h2>
        <pre className="whitespace-pre-wrap rounded-card border border-hairline bg-canvas px-3 py-2.5 font-mono text-mono-sm">
          {group.normalisedMessage || '(no message)'}
        </pre>
      </section>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
        <Evidence group={group} />
        <OverrideForm group={group} />
      </div>
      <section aria-labelledby="occurrences" className="flex flex-col gap-1.5">
        <h2 id="occurrences" className={heading}>
          Occurrences · newest first
          {group.occurrences.length < group.occurrenceCount
            ? ` · ${group.occurrences.length} of ${group.occurrenceCount}`
            : ''}
        </h2>
        <div className="overflow-x-auto rounded-panel border border-hairline bg-surface">
          <table className="w-full text-left text-body-sm">
            <caption className="sr-only">Occurrences of this failure, newest first</caption>
            <thead className="border-b border-hairline text-text-muted">
              <tr>
                {['Run', 'Engine', 'Case', 'Status', 'When', 'Artifacts', 'Raw message'].map(
                  (h) => (
                    <th key={h} scope="col" className="px-3 py-2 font-medium">
                      {h}
                    </th>
                  ),
                )}
              </tr>
            </thead>
            <tbody>
              {group.occurrences.map((o) => (
                <OccurrenceRow key={`${o.attemptId}-${o.caseKey ?? 'target'}`} o={o} />
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section aria-labelledby="audit" className="flex flex-col gap-1.5">
        <h2 id="audit" className={heading}>
          Audit history
        </h2>
        {group.audit.length === 0 ? (
          <p className="text-body-sm text-text-muted">No override yet: the label is the rule’s.</p>
        ) : (
          <ul className="flex flex-col gap-1 font-mono text-mono-sm text-text-secondary">
            {group.audit.map((a) => (
              <li key={a.id}>
                {formatAbsolute(a.occurredAt)} · {a.actor} · {String(a.metadata.from)} →{' '}
                {String(a.metadata.to)} · “{String(a.metadata.reason)}”
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

export function FailureGroupPage() {
  const { groupId = '' } = useParams();
  const group = useFailureGroup(groupId);
  return (
    <Page title="Failure group">
      <Link to="/failures" className={`${secondaryButton} self-start`}>
        All failure groups
      </Link>
      {group.isPending ? <LoadingState label="Loading failure group…" /> : null}
      {group.error ? <ErrorState error={group.error} /> : null}
      {group.data ? <Group group={group.data} /> : null}
    </Page>
  );
}

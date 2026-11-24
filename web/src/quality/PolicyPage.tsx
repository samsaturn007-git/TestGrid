import { useId, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useParams, useSearchParams } from 'react-router';
import { ApiError } from '../api/client.ts';
import { useCurrentProject, useQualityPolicy, useUpdatePolicy } from '../api/queries.ts';
import type { AutoMode, QualityPolicy, QualityPolicyView } from '../api/types.ts';
import { primaryButton, secondaryButton } from '../components/buttons.ts';
import { ErrorState, LoadingState } from '../components/States.tsx';
import { formatAbsolute } from '../lib/time.ts';
import { Page } from '../pages/Page.tsx';

const TARGETS = ['local:chromium', 'local:firefox', 'local:webkit'] as const;
const AUTO_MODES: AutoMode[] = ['OFF', 'SUGGEST', 'AUTO'];

/** `/policy` goes to the caller's project once it is known. */
export function PolicyRedirect() {
  const { project, isPending, error } = useCurrentProject();
  if (project) return <Navigate replace to={`/projects/${project.id}/policy`} />;
  return (
    <Page title="Quality policy">
      {isPending ? <LoadingState label="Loading projects…" /> : null}
      {error ? <ErrorState error={error} /> : null}
    </Page>
  );
}

/**
 * `/projects/:projectId/policy[?version=N]` (PRD-08 FR-7): the policy the gate applies, its
 * version history, and a form that saves the next version. Fixed rule types with numbers and
 * toggles only: there is nowhere to type an expression, a regex or a script.
 */
export function PolicyPage() {
  const { projectId = '' } = useParams();
  const [search] = useSearchParams();
  const raw = search.get('version');
  const version = raw !== null && /^[1-9][0-9]{0,8}$/.test(raw) ? Number(raw) : null;
  const policy = useQualityPolicy(projectId, version);

  return (
    <Page
      title="Quality policy"
      description="The rules the gate applies when a run finishes. Saving creates a new version; runs already evaluated keep the version they were judged by."
    >
      {policy.isPending ? <LoadingState label="Loading the policy…" /> : null}
      {policy.error ? (
        <ErrorState
          error={policy.error}
          action={
            version !== null ? (
              <Link to={`/projects/${projectId}/policy`} className={secondaryButton}>
                View the current version
              </Link>
            ) : undefined
          }
        />
      ) : null}
      {policy.data ? <PolicyView view={policy.data} projectId={projectId} /> : null}
    </Page>
  );
}

function PolicyView({ view, projectId }: { view: QualityPolicyView; projectId: string }) {
  const [saved, setSaved] = useState<number | null>(null);
  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
      <div className="flex flex-col gap-6">
        {!view.active ? (
          <p
            role="note"
            className="flex flex-wrap items-center gap-3 rounded-card border border-warning-rim bg-warning-tint px-3 py-2 text-body"
          >
            Version {view.version} is not the active policy.
            <Link to={`/projects/${projectId}/policy`} className="underline">
              View the current version
            </Link>
          </p>
        ) : null}
        {saved !== null && saved === view.version ? (
          <p role="status" className="text-body text-success">
            Saved as version {saved}. Runs that finish from now on are judged by it.
          </p>
        ) : null}
        <Rules view={view} />
        {view.active ? (
          // Keyed by version: after a save the form starts again from the new version.
          <EditForm key={view.version} view={view} projectId={projectId} onSaved={setSaved} />
        ) : null}
      </div>
      <Versions view={view} projectId={projectId} />
    </div>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_auto] gap-3 border-b border-hairline-soft px-4 py-2.5 last:border-b-0">
      <dt className="text-body text-text-secondary">{label}</dt>
      <dd className="text-right font-mono text-mono-sm">{children}</dd>
    </div>
  );
}

const yes = (v: boolean) => (v ? 'yes' : 'no');

function Rules({ view }: { view: QualityPolicyView }) {
  const p = view.policy;
  return (
    <section
      aria-labelledby="policy-rules"
      className="overflow-hidden rounded-card border border-hairline bg-surface"
    >
      <div className="flex items-center gap-2.5 border-b border-hairline px-4 py-3">
        <h2 id="policy-rules" className="text-body font-semibold">
          Version {view.version}
        </h2>
        {view.active ? <CurrentTag /> : null}
        <span className="ml-auto font-mono text-[11px] text-text-secondary">
          <time dateTime={view.createdAt}>{formatAbsolute(view.createdAt)}</time>
        </span>
      </div>
      <dl>
        <Row label="Required targets (missing or inconclusive → INDETERMINATE)">
          {p.requiredTargets.join(', ')}
        </Row>
        <Row label="A failed critical case blocks">{yes(p.failOnCriticalCase)}</Row>
        <Row label="Failed cases allowed (more fails)">{p.maxFailedCases}</Row>
        <Row label="An INFRA_ERROR target blocks">{yes(p.failOnInfraError)}</Row>
        <Row label="Flaky rate allowed (above fails)">{p.maxFlakyRate}</Row>
        <Row label="Duration regression vs last green">
          {p.durationRegression.enabled
            ? `> ${p.durationRegression.maxRatio}× and > +${p.durationRegression.minDeltaMs} ms · ${p.durationRegression.blocking ? 'blocks' : 'reported'}`
            : 'off'}
        </Row>
        <Row label="Quarantines honoured">{yes(p.quarantine.honor)}</Row>
        <Row label="Active quarantines allowed">{p.quarantine.maxActive}</Row>
        <Row label="Auto mode">{p.quarantine.autoMode}</Row>
      </dl>
    </section>
  );
}

function CurrentTag() {
  return (
    <span className="rounded-[4px] border border-success-rim bg-success-tint px-[5px] py-[3px] font-mono text-[10px] font-medium leading-none text-success">
      CURRENT
    </span>
  );
}

function Versions({ view, projectId }: { view: QualityPolicyView; projectId: string }) {
  return (
    <section
      aria-labelledby="policy-versions"
      className="self-start overflow-hidden rounded-card border border-hairline bg-surface"
    >
      <h2
        id="policy-versions"
        className="border-b border-hairline px-4 py-3 text-body font-semibold"
      >
        Versions
      </h2>
      <ol aria-label="Policy versions, newest first">
        {view.versions.map((v) => {
          const shown = v.version === view.version;
          return (
            <li
              key={v.id}
              className={`flex items-center gap-2.5 border-b border-hairline-soft px-4 py-2.5 last:border-b-0 ${shown ? 'bg-raised' : ''}`}
            >
              <Link
                to={`/projects/${projectId}/policy${v.active ? '' : `?version=${v.version}`}`}
                aria-current={shown ? 'page' : undefined}
                className="font-mono text-[13px] font-semibold"
              >
                v{v.version}
              </Link>
              {v.active ? <CurrentTag /> : null}
              <span className="ml-auto font-mono text-[11px] text-text-secondary">
                <time dateTime={v.createdAt}>{formatAbsolute(v.createdAt)}</time>
              </span>
            </li>
          );
        })}
      </ol>
      <p className="border-t border-hairline px-4 py-2.5 text-body-sm text-text-muted">
        Each change and its reason are kept in the audit log.
      </p>
    </section>
  );
}

const input =
  'h-[30px] w-24 rounded-chip border border-hairline-strong bg-canvas px-2 font-mono text-mono-sm text-text';

function NumberField(props: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  min: number;
  max: number;
  step?: number;
}) {
  const id = useId();
  return (
    <div className="flex items-center justify-between gap-3">
      <label htmlFor={id} className="text-body-sm">
        {props.label}
      </label>
      <input
        id={id}
        type="number"
        className={input}
        value={Number.isNaN(props.value) ? '' : props.value}
        min={props.min}
        max={props.max}
        step={props.step ?? 1}
        required
        onChange={(e) => props.onChange(e.target.valueAsNumber)}
      />
    </div>
  );
}

function Toggle(props: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex items-center gap-2 text-body-sm">
      <input
        type="checkbox"
        checked={props.checked}
        onChange={(e) => props.onChange(e.target.checked)}
        className="size-4 accent-current"
      />
      {props.label}
    </label>
  );
}

function EditForm({
  view,
  projectId,
  onSaved,
}: {
  view: QualityPolicyView;
  projectId: string;
  onSaved: (version: number) => void;
}) {
  const update = useUpdatePolicy(projectId);
  const [p, setP] = useState<QualityPolicy>(view.policy);
  const [reason, setReason] = useState('');
  const reasonId = useId();
  const autoId = useId();
  const problem = update.error instanceof ApiError ? update.error.problem : null;
  const set = <K extends keyof QualityPolicy>(key: K, v: QualityPolicy[K]) =>
    setP((old) => ({ ...old, [key]: v }));
  const setDuration = (patch: Partial<QualityPolicy['durationRegression']>) =>
    setP((old) => ({ ...old, durationRegression: { ...old.durationRegression, ...patch } }));
  const setQuarantine = (patch: Partial<QualityPolicy['quarantine']>) =>
    setP((old) => ({ ...old, quarantine: { ...old.quarantine, ...patch } }));
  const noTarget = p.requiredTargets.length === 0;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    update.mutate({ reason, policy: p }, { onSuccess: (saved) => onSaved(saved.version) });
  };

  return (
    <form
      aria-labelledby="policy-edit"
      onSubmit={submit}
      className="flex flex-col gap-4 rounded-card border border-hairline bg-surface p-4"
    >
      <h2 id="policy-edit" className="text-body font-semibold">
        Edit as v{(view.versions[0]?.version ?? view.version) + 1}
      </h2>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-body-sm text-text-secondary">Required targets</legend>
        <div className="flex flex-wrap gap-4">
          {TARGETS.map((t) => (
            <Toggle
              key={t}
              label={t}
              checked={p.requiredTargets.includes(t)}
              onChange={(on) =>
                set(
                  'requiredTargets',
                  on ? [...p.requiredTargets, t] : p.requiredTargets.filter((x) => x !== t),
                )
              }
            />
          ))}
        </div>
        {noTarget ? (
          <p className="text-body-sm text-danger">Choose at least one required target.</p>
        ) : null}
      </fieldset>
      <fieldset className="flex flex-col gap-2.5">
        <legend className="mb-1 text-body-sm text-text-secondary">Failures</legend>
        <Toggle
          label="A failed critical case blocks"
          checked={p.failOnCriticalCase}
          onChange={(v) => set('failOnCriticalCase', v)}
        />
        <NumberField
          label="Failed cases allowed"
          value={p.maxFailedCases}
          min={0}
          max={1000}
          onChange={(v) => set('maxFailedCases', v)}
        />
        <Toggle
          label="An INFRA_ERROR target blocks"
          checked={p.failOnInfraError}
          onChange={(v) => set('failOnInfraError', v)}
        />
        <NumberField
          label="Flaky rate allowed (0–1)"
          value={p.maxFlakyRate}
          min={0}
          max={1}
          step={0.01}
          onChange={(v) => set('maxFlakyRate', v)}
        />
      </fieldset>
      <fieldset className="flex flex-col gap-2.5">
        <legend className="mb-1 text-body-sm text-text-secondary">
          Duration regression against the last green run
        </legend>
        <Toggle
          label="Check durations"
          checked={p.durationRegression.enabled}
          onChange={(v) => setDuration({ enabled: v })}
        />
        <NumberField
          label="Slower than base ×"
          value={p.durationRegression.maxRatio}
          min={1.01}
          max={10}
          step={0.01}
          onChange={(v) => setDuration({ maxRatio: v })}
        />
        <NumberField
          label="and slower by more than (ms)"
          value={p.durationRegression.minDeltaMs}
          min={0}
          max={600000}
          onChange={(v) => setDuration({ minDeltaMs: v })}
        />
        <Toggle
          label="A regression blocks"
          checked={p.durationRegression.blocking}
          onChange={(v) => setDuration({ blocking: v })}
        />
      </fieldset>
      <fieldset className="flex flex-col gap-2.5">
        <legend className="mb-1 text-body-sm text-text-secondary">Quarantine</legend>
        <Toggle
          label="Honour quarantines"
          checked={p.quarantine.honor}
          onChange={(v) => setQuarantine({ honor: v })}
        />
        <NumberField
          label="Active quarantines allowed"
          value={p.quarantine.maxActive}
          min={0}
          max={50}
          onChange={(v) => setQuarantine({ maxActive: v })}
        />
        <div className="flex items-center justify-between gap-3">
          <label htmlFor={autoId} className="text-body-sm">
            Auto mode
          </label>
          <select
            id={autoId}
            value={p.quarantine.autoMode}
            onChange={(e) => setQuarantine({ autoMode: e.target.value as AutoMode })}
            className="h-[30px] rounded-chip border border-hairline-strong bg-canvas px-2 font-mono text-mono-sm text-text"
          >
            {AUTO_MODES.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </div>
        <p className="text-body-sm text-text-muted">
          SUGGEST offers FLAKY cases on the quarantines page. AUTO quarantines a case labelled FLAKY
          over at least 10 results for 7 days, from the run after the one that triggered it.
        </p>
      </fieldset>
      <label htmlFor={reasonId} className="text-body-sm text-text-secondary">
        Reason <span className="text-text-muted">required, kept in the audit log</span>
      </label>
      <textarea
        id={reasonId}
        required
        maxLength={500}
        rows={2}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        className="resize-none rounded-chip border border-hairline-strong bg-canvas p-2 text-body-sm text-text"
      />
      {problem ? (
        <p role="alert" className="text-body-sm text-danger">
          {problem.detail}
          {problem.errors?.map((e) => ` ${e.field}: ${e.message}.`).join('')}
        </p>
      ) : update.error ? (
        <p role="alert" className="text-body-sm text-danger">
          Could not save the policy. Try again.
        </p>
      ) : null}
      <button
        type="submit"
        className={`${primaryButton} self-start`}
        disabled={update.isPending || reason.trim() === '' || noTarget}
        aria-busy={update.isPending}
      >
        Save as a new version
      </button>
    </form>
  );
}

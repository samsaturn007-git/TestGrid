import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { ApiError } from '../api/client.ts';
import { useCapabilities, useCreateRun, useCurrentProject, useSuites } from '../api/queries.ts';
import type { FieldError, Project, RunResponse, Suite } from '../api/types.ts';
import { iconButton, primaryButton, secondaryButton } from '../components/buttons.ts';
import { EngineMark } from '../components/Engine.tsx';
import { ErrorState, LoadingState } from '../components/States.tsx';
import {
  apiFieldErrors,
  buildRequest,
  defaultFixtureMode,
  ENGINES,
  FIXTURE_DESCRIPTIONS,
  IdempotencyKeys,
  initialValues,
  runnableSuites,
  validate,
  type FormErrors,
  type FormField,
  type NewRunValues,
} from './newRunForm.ts';

const FIELD_ORDER: FormField[] = ['suite', 'engines', 'fixtureMode', 'seed', 'branch', 'commitSha'];

const inputClass =
  'h-9 rounded-chip border border-hairline-strong bg-surface px-2.5 text-body text-text aria-invalid:border-danger disabled:text-text-muted';

/**
 * The new-run dialog (PRD-05 FR-2). A native <dialog> opened with showModal(), so the browser
 * makes the rest of the page inert, traps focus and closes it on Esc.
 */
export function NewRunDialog({
  initialSuiteKey,
  onClose,
}: {
  initialSuiteKey: string | undefined;
  onClose: (options?: { navigating?: boolean }) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const busy = useRef(false);
  const { project, error: projectError } = useCurrentProject();
  const suites = useSuites(project?.id);
  const capabilities = useCapabilities();

  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal();
    return () => {
      if (dialog?.open) dialog.close();
    };
  }, []);

  const loadError = projectError ?? suites.error ?? capabilities.error;
  let body: ReactNode;
  if (loadError) {
    body = (
      <div className="px-5 py-4">
        <ErrorState error={loadError} />
      </div>
    );
  } else if (!project || !suites.data || !capabilities.data) {
    body = (
      <div className="px-5 py-4">
        <LoadingState label="Loading suites…" />
      </div>
    );
  } else {
    body = (
      <NewRunForm
        project={project}
        suites={runnableSuites(suites.data.items)}
        fixturesAllowed={capabilities.data.fixturesAllowed}
        initialSuiteKey={initialSuiteKey}
        onBusy={(value) => {
          busy.current = value;
        }}
        onClose={onClose}
      />
    );
  }

  return (
    <dialog
      ref={ref}
      aria-labelledby="new-run-title"
      onCancel={(event) => {
        // Esc: React owns open/closed, and a request in flight finishes before the dialog goes.
        event.preventDefault();
        if (!busy.current) onClose();
      }}
      className="m-auto w-[min(560px,calc(100vw-32px))] rounded-dialog border border-hairline-strong bg-overlay p-0 text-text shadow-dialog backdrop:bg-scrim"
    >
      <div className="sticky top-0 z-10 flex items-center justify-between border-b border-hairline bg-overlay px-5 py-4">
        <h2 id="new-run-title" className="text-heading">
          New run
        </h2>
        <button
          type="button"
          aria-label="Close"
          className={iconButton}
          onClick={() => {
            if (!busy.current) onClose();
          }}
        >
          <svg
            width="14"
            height="14"
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            aria-hidden="true"
          >
            <path d="M4 4l8 8M12 4l-8 8" />
          </svg>
        </button>
      </div>
      {body}
    </dialog>
  );
}

function NewRunForm({
  project,
  suites,
  fixturesAllowed,
  initialSuiteKey,
  onBusy,
  onClose,
}: {
  project: Project;
  suites: Suite[];
  fixturesAllowed: boolean;
  initialSuiteKey: string | undefined;
  onBusy: (busy: boolean) => void;
  onClose: (options?: { navigating?: boolean }) => void;
}) {
  const id = useId();
  const navigate = useNavigate();
  const createRun = useCreateRun(project.id);
  const keys = useRef(new IdempotencyKeys());
  const formRef = useRef<HTMLFormElement>(null);

  const firstSuite = suites.find((s) => s.suiteKey === initialSuiteKey) ?? suites[0];
  const [values, setValues] = useState<NewRunValues>(() =>
    initialValues(firstSuite, fixturesAllowed),
  );
  const [errors, setErrors] = useState<FormErrors>({});
  const [failure, setFailure] = useState<{ error: unknown; unmapped: FieldError[] } | null>(null);
  const [replay, setReplay] = useState<RunResponse | null>(null);

  const suite = suites.find((s) => s.suiteKey === values.suiteKey);
  const pending = createRun.isPending;
  useEffect(() => onBusy(pending), [pending, onBusy]);

  // Focus moves after the render that re-enables the fields: an answer arrives while the
  // fieldset is still disabled, and a disabled input cannot take focus.
  const focusAfterRender = useRef<FormErrors | null>(null);
  useEffect(() => {
    if (pending || !focusAfterRender.current) return;
    focusFirstError(focusAfterRender.current);
    focusAfterRender.current = null;
  });

  if (suites.length === 0) {
    return (
      <div className="flex flex-col gap-2 px-5 py-4 text-body text-text-secondary">
        <p>No suite can run: the manifest has no enabled suite for {project.name}.</p>
        <p className="text-text-muted">
          Add one to <code className="font-mono">worker/manifest/suites.json</code> and restart the
          API.
        </p>
      </div>
    );
  }

  const fieldId = (field: FormField) => `${id}-${field}`;
  const errorId = (field: FormField) => `${id}-${field}-error`;
  const invalid = (field: FormField) =>
    errors[field]
      ? { 'aria-invalid': true as const, 'aria-describedby': errorId(field) }
      : { 'aria-invalid': false as const };

  function update(patch: Partial<NewRunValues>) {
    setValues((v) => ({ ...v, ...patch }));
    setReplay(null);
  }

  function chooseSuite(suiteKey: string) {
    const next = suites.find((s) => s.suiteKey === suiteKey);
    update({
      suiteKey,
      engines: next ? ENGINES.filter((e) => next.allowedEngines.includes(e)) : [],
      fixtureMode: defaultFixtureMode(next, fixturesAllowed),
    });
    setErrors({});
  }

  function focusFirstError(found: FormErrors) {
    const first = FIELD_ORDER.find((f) => found[f]);
    if (!first) return;
    // Engines and fixture mode are groups: focus their first control.
    const target = formRef.current?.querySelector<HTMLElement>(
      `#${CSS.escape(fieldId(first))}, [data-field="${first}"] input`,
    );
    target?.focus();
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    const found = validate(values, suite);
    setErrors(found);
    setFailure(null);
    setReplay(null);
    if (Object.keys(found).length > 0) {
      focusFirstError(found);
      return;
    }
    const request = buildRequest(values);
    createRun.mutate(
      { ...request, idempotencyKey: keys.current.keyFor(request) },
      {
        onSuccess: (result) => {
          if (result.replayed) {
            setReplay(result.data);
            return;
          }
          onClose({ navigating: true });
          void navigate(`/runs/${result.data.id}`);
        },
        onError: (error) => {
          const problem = error instanceof ApiError ? error.problem : null;
          const { byField, unmapped } = apiFieldErrors(problem?.errors);
          setErrors(byField);
          setFailure({ error, unmapped });
          focusAfterRender.current = byField;
        },
      },
    );
  }

  const clientErrorCount = failure ? 0 : Object.keys(errors).length;
  const showFixtures = fixturesAllowed && suite !== undefined && suite.fixtureModes.length > 0;
  const targetCount = values.engines.length;

  return (
    <form ref={formRef} noValidate onSubmit={submit}>
      <fieldset
        disabled={pending}
        className="m-0 flex min-w-0 flex-col gap-[18px] border-0 px-5 py-[18px]"
      >
        {clientErrorCount > 0 ? (
          <Callout tone="danger">
            {clientErrorCount === 1
              ? 'One field needs attention before this run can start.'
              : `${clientErrorCount} fields need attention before this run can start.`}
          </Callout>
        ) : null}
        {failure ? <SubmitFailure error={failure.error} unmapped={failure.unmapped} /> : null}
        {replay ? (
          <Callout tone="info" title="This run already exists">
            TestGrid already accepted this exact request, so it returned the existing run instead of
            starting a second one.{' '}
            <Link
              to={`/runs/${replay.id}`}
              onClick={() => onClose({ navigating: true })}
              className="font-medium"
            >
              Open run <span className="font-mono">{replay.id.slice(0, 8)}</span>
            </Link>
          </Callout>
        ) : null}

        <div className="flex flex-col gap-1.5">
          <label htmlFor={fieldId('suite')} className="text-body font-medium">
            Suite
          </label>
          <select
            // showModal() focuses the dialog's autofocus element, so focus starts on the
            // first field rather than on Close.
            autoFocus
            id={fieldId('suite')}
            value={values.suiteKey}
            onChange={(e) => chooseSuite(e.target.value)}
            className={inputClass}
            {...invalid('suite')}
          >
            {suites.map((s) => (
              <option key={s.id} value={s.suiteKey}>
                {`${s.displayName} · ${s.suiteKey} · ${s.caseCount} cases`}
              </option>
            ))}
          </select>
          <InlineError id={errorId('suite')} message={errors.suite} />
        </div>

        <fieldset
          data-field="engines"
          className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0"
          {...(errors.engines ? { 'aria-describedby': errorId('engines') } : {})}
        >
          <legend className="mb-2 p-0 text-body font-medium">
            Engines{' '}
            <span className="font-normal text-text-muted">
              · 1 to 3, from the suite&apos;s allowed engines
            </span>
          </legend>
          <div className="flex flex-wrap gap-2">
            {ENGINES.filter((e) => suite?.allowedEngines.includes(e)).map((engine) => {
              const checked = values.engines.includes(engine);
              return (
                <label
                  key={engine}
                  className={[
                    'flex h-[38px] min-w-[140px] flex-1 cursor-pointer items-center gap-2 rounded-chip border px-2.5 font-mono text-mono font-medium',
                    errors.engines
                      ? 'border-danger-rim bg-surface'
                      : checked
                        ? 'border-info-rim bg-info-tint'
                        : 'border-hairline-strong bg-surface',
                  ].join(' ')}
                >
                  <input
                    type="checkbox"
                    name="engines"
                    value={engine}
                    checked={checked}
                    onChange={(e) =>
                      update({
                        engines: e.target.checked
                          ? [...values.engines, engine]
                          : values.engines.filter((x) => x !== engine),
                      })
                    }
                    className="m-0 size-4 accent-info"
                  />
                  <EngineMark engine={engine} />
                  {engine}
                </label>
              );
            })}
          </div>
          <InlineError id={errorId('engines')} message={errors.engines} />
        </fieldset>

        {showFixtures ? (
          <fieldset
            data-field="fixtureMode"
            className="m-0 flex min-w-0 flex-col gap-1 rounded-card border border-dashed border-hairline-strong p-3.5"
            {...(errors.fixtureMode ? { 'aria-describedby': errorId('fixtureMode') } : {})}
          >
            <legend className="px-1.5 text-body font-medium">
              Fixture mode{' '}
              <span className="ml-1.5 rounded-tag border border-hairline-strong px-1.5 py-0.5 font-mono text-[10px] font-medium tracking-[0.06em] text-text-muted">
                LOCAL PROFILE ONLY
              </span>
            </legend>
            {suite.fixtureModes.map((mode) => (
              <label
                key={mode}
                className={[
                  'grid cursor-pointer grid-cols-[20px_minmax(0,1fr)] gap-2 rounded-chip px-2 py-[7px]',
                  values.fixtureMode === mode ? 'bg-info-tint' : '',
                ].join(' ')}
              >
                <input
                  type="radio"
                  name="fixtureMode"
                  value={mode}
                  checked={values.fixtureMode === mode}
                  onChange={() => update({ fixtureMode: mode })}
                  className="m-0 mt-px size-4 accent-info"
                />
                <span className="flex flex-col gap-0.5">
                  <span className="font-mono text-mono-sm font-medium">{mode}</span>
                  <span className="text-body-sm text-text-muted">{FIXTURE_DESCRIPTIONS[mode]}</span>
                </span>
              </label>
            ))}
            <InlineError id={errorId('fixtureMode')} message={errors.fixtureMode} />
            <div className="mt-2 flex flex-wrap items-center gap-2.5">
              <label htmlFor={fieldId('seed')} className="text-body font-medium">
                Seed
              </label>
              <input
                id={fieldId('seed')}
                type="text"
                inputMode="numeric"
                autoComplete="off"
                value={values.seed}
                onChange={(e) => update({ seed: e.target.value })}
                className={`${inputClass} h-8 w-[110px] font-mono`}
                {...invalid('seed')}
              />
              <span className="text-body-sm text-text-muted">Same seed, same behaviour.</span>
            </div>
            <InlineError id={errorId('seed')} message={errors.seed} />
          </fieldset>
        ) : null}

        <div className="grid grid-cols-2 gap-3">
          <TextField
            id={fieldId('branch')}
            label="Branch"
            placeholder="main"
            value={values.branch}
            onChange={(branch) => update({ branch })}
            error={errors.branch}
            errorId={errorId('branch')}
          />
          <TextField
            id={fieldId('commitSha')}
            label="Commit SHA"
            placeholder="7–64 hex characters"
            value={values.commitSha}
            onChange={(commitSha) => update({ commitSha })}
            error={errors.commitSha}
            errorId={errorId('commitSha')}
          />
        </div>

        <p className="text-body-sm text-text-muted">
          There are no URL, script or flag fields. A run can only use a registered suite.
        </p>
      </fieldset>

      <div className="sticky bottom-0 z-10 flex flex-wrap items-center justify-between gap-2.5 border-t border-hairline bg-overlay px-5 py-3.5">
        <span className="text-body-sm text-text-muted">
          {targetCount === 1 ? '1 target' : `${targetCount} targets`} · each case runs on each
          engine
        </span>
        <div className="flex gap-2">
          <button
            type="button"
            className={secondaryButton}
            disabled={pending}
            onClick={() => onClose()}
          >
            Cancel
          </button>
          <button
            type="submit"
            className={primaryButton}
            aria-busy={pending}
            aria-disabled={pending}
          >
            {pending ? (
              <>
                <svg
                  width="14"
                  height="14"
                  viewBox="0 0 16 16"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  aria-hidden="true"
                  className="motion-safe:animate-spin"
                >
                  <path d="M13.5 8A5.5 5.5 0 1 1 8 2.5" />
                </svg>
                Starting run…
              </>
            ) : (
              'Start run'
            )}
          </button>
        </div>
      </div>
    </form>
  );
}

function TextField({
  id,
  label,
  placeholder,
  value,
  onChange,
  error,
  errorId,
}: {
  id: string;
  label: string;
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
  error: string | undefined;
  errorId: string;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <label htmlFor={id} className="text-body font-medium">
        {label} <span className="font-normal text-text-muted">optional</span>
      </label>
      <input
        id={id}
        type="text"
        autoComplete="off"
        spellCheck={false}
        placeholder={placeholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={`${inputClass} font-mono`}
        aria-invalid={error ? true : false}
        {...(error ? { 'aria-describedby': errorId } : {})}
      />
      <InlineError id={errorId} message={error} />
    </div>
  );
}

function InlineError({ id, message }: { id: string; message: string | undefined }) {
  if (!message) return null;
  return (
    <span id={id} className="inline-flex items-center gap-1.5 text-body-sm text-danger">
      <svg
        width="12"
        height="12"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        aria-hidden="true"
      >
        <path d="M8 2.5a5.5 5.5 0 1 1 0 11a5.5 5.5 0 1 1 0-11zM6 6l4 4M10 6l-4 4" />
      </svg>
      {message}
    </span>
  );
}

function Callout({
  tone,
  title,
  children,
}: {
  tone: 'danger' | 'info';
  title?: string;
  children: ReactNode;
}) {
  const danger = tone === 'danger';
  return (
    <div
      role={danger ? 'alert' : 'status'}
      className={[
        'flex gap-2.5 rounded-card border px-3 py-2.5 text-body',
        danger ? 'border-danger-rim bg-danger-tint' : 'border-info-rim bg-info-tint',
      ].join(' ')}
    >
      <svg
        width="16"
        height="16"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className={`mt-0.5 shrink-0 ${danger ? 'text-danger' : 'text-info'}`}
      >
        <path
          d={
            danger
              ? 'M8 2.5a5.5 5.5 0 1 1 0 11a5.5 5.5 0 1 1 0-11zM8 5v3.5M8 10.8v.1'
              : 'M8 2.5a5.5 5.5 0 1 1 0 11a5.5 5.5 0 1 1 0-11zM8 7.3v3.4M8 5.1v.1'
          }
        />
      </svg>
      <div className="flex min-w-0 flex-col gap-1">
        {title ? <span className="font-semibold">{title}</span> : null}
        <div className="text-text-secondary">{children}</div>
      </div>
    </div>
  );
}

/** A refused or failed POST: the Problem title, detail, any unmapped field errors, trace ID. */
function SubmitFailure({ error, unmapped }: { error: unknown; unmapped: FieldError[] }) {
  const apiError = error instanceof ApiError ? error : null;
  const problem = apiError?.problem ?? null;
  if (!problem) {
    return (
      <Callout tone="danger" title="The API could not be reached">
        {apiError && apiError.status > 0
          ? `The request failed with HTTP ${apiError.status}. `
          : 'The request may or may not have arrived. '}
        Starting again with the same choices is safe: it returns the same run instead of a second
        one.
      </Callout>
    );
  }
  return (
    <Callout tone="danger" title={`${problem.title} · ${problem.status}`}>
      <p>{problem.detail}</p>
      {unmapped.length > 0 ? (
        <ul className="mt-1 list-disc pl-5">
          {unmapped.map((e) => (
            <li key={`${e.field}:${e.message}`}>
              <code className="font-mono">{e.field}</code>: {e.message}
            </li>
          ))}
        </ul>
      ) : null}
      <p className="mt-1 font-mono text-mono-sm text-text-muted">
        Trace ID <code className="select-all">{problem.traceId}</code>
      </p>
    </Callout>
  );
}

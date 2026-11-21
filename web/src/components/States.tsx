import type { ReactNode } from 'react';
import { ApiError } from '../api/client.ts';

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <section className="flex flex-col items-start gap-2 rounded-panel border border-hairline bg-surface px-6 py-8">
      <h2 className="text-heading">{title}</h2>
      {children ? <div className="text-body text-text-muted">{children}</div> : null}
    </section>
  );
}

export function LoadingState({ label }: { label: string }) {
  return (
    <p role="status" className="text-body text-text-muted">
      {label}
    </p>
  );
}

/** Shows the Problem Details title and detail, with the trace ID as small print. */
export function ErrorState({ error, action }: { error: unknown; action?: ReactNode }) {
  const problem = error instanceof ApiError ? error.problem : null;
  const title = problem?.title ?? 'The API could not be reached';
  const detail =
    problem?.detail ??
    (error instanceof ApiError && error.status > 0
      ? `The request failed with HTTP ${error.status}.`
      : 'Check that the API is running, then try again.');

  return (
    <section
      role="alert"
      className="flex flex-col items-start gap-2 rounded-panel border border-danger-rim bg-surface px-6 py-5"
    >
      <h2 className="text-heading text-danger">{title}</h2>
      <p className="text-body text-text-secondary">{detail}</p>
      {problem?.traceId ? (
        <p className="text-mono-sm font-mono text-text-muted">
          Trace ID <code className="select-all text-text-secondary">{problem.traceId}</code>
        </p>
      ) : null}
      {action}
    </section>
  );
}

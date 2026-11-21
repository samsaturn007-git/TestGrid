import { useState } from 'react';
import { useParams } from 'react-router';
import { useCurrentProject, useSuite, useSuites } from '../api/queries.ts';
import type { Suite } from '../api/types.ts';
import { primaryButton, secondaryButton } from '../components/buttons.ts';
import { EngineChip } from '../components/Engine.tsx';
import { EmptyState, ErrorState, LoadingState } from '../components/States.tsx';
import { useNewRun } from '../runs/newRunContext.ts';
import { Page } from './Page.tsx';

/** `/projects/:projectId/suites` (PRD-05 FR-2): the manifest's current suites, read-only. */
export function SuitesPage() {
  const { projectId } = useParams();
  const { project } = useCurrentProject();
  const suites = useSuites(projectId);
  const items = suites.data?.items ?? [];
  const projectName = project && project.id === projectId ? project.name : null;

  const description = suites.data ? (
    <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
      <span>
        {items.length === 1 ? '1 registered suite' : `${items.length} registered suites`}
        {projectName ? ` in ${projectName}` : ''}
      </span>
      <span className="inline-flex items-center gap-2">
        <svg
          width="14"
          height="14"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M4 7V5a4 4 0 0 1 8 0v2M3 7h10v7H3z" />
        </svg>
        Suites come from the registered manifest and can&apos;t be edited here.
      </span>
    </span>
  ) : undefined;

  return (
    <Page title="Suites" description={description}>
      {suites.isPending ? <LoadingState label="Loading suites…" /> : null}
      {suites.error ? <ErrorState error={suites.error} /> : null}
      {suites.data && items.length === 0 ? (
        <EmptyState title="No suites registered">
          Add a suite to <code className="font-mono">worker/manifest/suites.json</code> and restart
          the API. It appears here once the manifest loads.
        </EmptyState>
      ) : null}
      {items.map((suite) => (
        <SuiteCard key={suite.id} suite={suite} />
      ))}
    </Page>
  );
}

function formatTimeout(ms: number): string {
  return ms % 1000 === 0 ? `${ms / 1000} s` : `${(ms / 1000).toFixed(1)} s`;
}

function SuiteCard({ suite }: { suite: Suite }) {
  const [open, setOpen] = useState(false);
  const { openNewRun } = useNewRun();
  const headingId = `suite-${suite.id}`;
  const casesId = `suite-${suite.id}-cases`;
  const runnable = suite.enabled && suite.current;

  return (
    <article
      aria-labelledby={headingId}
      className="overflow-hidden rounded-panel border border-hairline bg-surface"
    >
      <div className="flex flex-wrap items-center gap-x-6 gap-y-4 px-5 py-[18px]">
        <div className="flex min-w-0 flex-[1_1_280px] flex-col gap-2">
          <div className="flex flex-wrap items-baseline gap-3">
            <h2 id={headingId} className="text-[18px] font-semibold leading-6">
              {suite.displayName}
            </h2>
            <span className="font-mono text-mono text-text-secondary">{suite.suiteKey}</span>
            {!suite.enabled ? (
              <span className="rounded-tag border border-hairline-strong px-1.5 py-0.5 text-body-sm text-text-muted">
                Disabled in the manifest
              </span>
            ) : null}
          </div>
          <dl className="flex flex-wrap gap-x-[18px] gap-y-1.5 text-body text-text-muted">
            <Fact term="Cases" value={String(suite.caseCount)} />
            <Fact term="Timeout" value={formatTimeout(suite.timeoutMs)} />
            <Fact term="Retries" value={String(suite.playwrightRetries)} />
            <Fact term="Revision" value={suite.revision} />
          </dl>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="mr-1 text-body-sm text-text-muted" id={`${headingId}-engines`}>
            Engines
          </span>
          <ul aria-labelledby={`${headingId}-engines`} className="flex flex-wrap gap-1.5">
            {suite.allowedEngines.map((engine) => (
              <li key={engine}>
                <EngineChip engine={engine} />
              </li>
            ))}
          </ul>
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            className={secondaryButton}
            aria-expanded={open}
            aria-controls={casesId}
            onClick={() => setOpen((v) => !v)}
          >
            <svg
              width="14"
              height="14"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d={open ? 'M4 10l4-4 4 4' : 'M4 6l4 4 4-4'} />
            </svg>
            {open ? 'Hide cases' : `Show ${suite.caseCount} cases`}
          </button>
          {runnable ? (
            <button
              type="button"
              className={primaryButton}
              aria-haspopup="dialog"
              aria-label={`Run ${suite.displayName}`}
              onClick={() => openNewRun(suite.suiteKey)}
            >
              <svg
                width="12"
                height="12"
                viewBox="0 0 16 16"
                fill="currentColor"
                aria-hidden="true"
              >
                <path d="M4.5 2.8v10.4L13 8z" />
              </svg>
              Run
            </button>
          ) : null}
        </div>
      </div>
      <div id={casesId} hidden={!open} className="border-t border-hairline">
        {open ? <CaseTable suite={suite} /> : null}
      </div>
    </article>
  );
}

function Fact({ term, value }: { term: string; value: string }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <dt>{term}</dt>
      <dd className="font-mono text-text">{value}</dd>
    </div>
  );
}

function CaseTable({ suite }: { suite: Suite }) {
  const detail = useSuite(suite.id, true);
  if (detail.isPending) {
    return (
      <div className="px-5 py-3">
        <LoadingState label="Loading cases…" />
      </div>
    );
  }
  if (detail.error) {
    return (
      <div className="px-5 py-3">
        <ErrorState error={detail.error} />
      </div>
    );
  }
  return (
    <table className="w-full border-collapse text-left">
      <caption className="sr-only">Cases in {suite.displayName}</caption>
      <thead className="bg-canvas">
        <tr className="border-b border-hairline-soft font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted">
          <th scope="col" className="w-14 py-2.5 pl-5 font-medium">
            #
          </th>
          <th scope="col" className="py-2.5 pr-3 font-medium">
            Case key
          </th>
          <th scope="col" className="py-2.5 pr-3 font-medium">
            Title
          </th>
          <th scope="col" className="py-2.5 pr-5 font-medium">
            Tag
          </th>
        </tr>
      </thead>
      <tbody>
        {detail.data.cases.map((c, i) => (
          <tr key={c.caseKey} className="h-10 border-b border-hairline-soft last:border-b-0">
            <td className="pl-5 font-mono text-mono-sm text-text-muted">
              {String(i + 1).padStart(2, '0')}
            </td>
            <td className="pr-3 font-mono text-mono">{c.caseKey}</td>
            <td className="pr-3 text-body text-text-secondary">
              {c.title}
              {c.critical ? (
                <span className="ml-2 rounded-tag border border-warning-rim px-1.5 py-0.5 text-body-sm text-warning">
                  critical
                </span>
              ) : null}
            </td>
            <td className="pr-5">
              <span className="rounded-tag border border-hairline-strong bg-overlay px-1.5 py-1 font-mono text-mono-sm text-text-secondary">
                {c.tag}
              </span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

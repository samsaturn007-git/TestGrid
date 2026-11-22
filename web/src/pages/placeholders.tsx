import { Link, Navigate } from 'react-router';
import { useCurrentProject } from '../api/queries.ts';
import { EmptyState, ErrorState, LoadingState } from '../components/States.tsx';
import { Page } from './Page.tsx';

// PRD-05 T1 ships the routes and the shell; each page's real content lands in its own task.
// Until then a page says so plainly instead of showing invented data (FR-7).
function NotBuiltYet({ task }: { task: string }) {
  return (
    <EmptyState title="Not built yet">
      This page arrives in PRD-05 {task}. It will show live data from the API.
    </EmptyState>
  );
}

export function OverviewPage() {
  return (
    <Page title="Overview">
      <NotBuiltYet task="T6" />
    </Page>
  );
}

export function WorkersPage() {
  return (
    <Page title="Workers">
      <NotBuiltYet task="T6" />
    </Page>
  );
}

/** `/suites` (and the `G S` shortcut) go to the caller's project once it is known. */
export function SuitesRedirect() {
  const { project, isPending, error } = useCurrentProject();
  if (project) return <Navigate replace to={`/projects/${project.id}/suites`} />;
  return (
    <Page title="Suites">
      {isPending ? <LoadingState label="Loading projects…" /> : null}
      {error ? <ErrorState error={error} /> : null}
      {!isPending && !error ? (
        <EmptyState title="No project">The API returned no project for this caller.</EmptyState>
      ) : null}
    </Page>
  );
}

export function NotFoundPage() {
  return (
    <Page title="Page not found">
      <EmptyState title="Nothing lives at this address">
        <Link to="/">Go to the overview</Link>
      </EmptyState>
    </Page>
  );
}

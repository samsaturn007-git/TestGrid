import { Link, Navigate } from 'react-router';
import { useCurrentProject } from '../api/queries.ts';
import { EmptyState, ErrorState, LoadingState } from '../components/States.tsx';
import { Page } from './Page.tsx';

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

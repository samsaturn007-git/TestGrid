import type { RouteObject } from 'react-router';
import { NotFoundPage, OverviewPage, SuitesRedirect, WorkersPage } from '../pages/placeholders.tsx';
import { RunsPage } from '../pages/RunsPage.tsx';
import { RunDetailRoute } from '../runs/detail/RunDetailPage.tsx';
import { SuitesPage } from '../pages/SuitesPage.tsx';
import { AppShell } from './AppShell.tsx';

// One route table for the browser router and for tests (createMemoryRouter).
export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'runs', element: <RunsPage /> },
      { path: 'runs/:runId', element: <RunDetailRoute /> },
      { path: 'suites', element: <SuitesRedirect /> },
      { path: 'projects/:projectId/suites', element: <SuitesPage /> },
      { path: 'workers', element: <WorkersPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

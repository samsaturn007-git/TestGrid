import type { RouteObject } from 'react-router';
import {
  NotFoundPage,
  OverviewPage,
  RunDetailPage,
  SuitesRedirect,
  WorkersPage,
} from '../pages/placeholders.tsx';
import { RunsPage } from '../pages/RunsPage.tsx';
import { SuitesPage } from '../pages/SuitesPage.tsx';
import { AppShell } from './AppShell.tsx';

// One route table for the browser router and for tests (createMemoryRouter).
export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'runs', element: <RunsPage /> },
      { path: 'runs/:runId', element: <RunDetailPage /> },
      { path: 'suites', element: <SuitesRedirect /> },
      { path: 'projects/:projectId/suites', element: <SuitesPage /> },
      { path: 'workers', element: <WorkersPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

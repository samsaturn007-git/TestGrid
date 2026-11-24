import type { RouteObject } from 'react-router';
import { NotFoundPage, SuitesRedirect } from '../pages/placeholders.tsx';
import { OverviewPage } from '../pages/OverviewPage.tsx';
import { RunsPage } from '../pages/RunsPage.tsx';
import { WorkersPage } from '../pages/WorkersPage.tsx';
import { RunDetailRoute } from '../runs/detail/RunDetailPage.tsx';
import { SuitesPage } from '../pages/SuitesPage.tsx';
import { CaseHistoryPage } from '../cases/CaseHistoryPage.tsx';
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
      { path: 'cases/:caseKey', element: <CaseHistoryPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

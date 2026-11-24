import type { RouteObject } from 'react-router';
import { NotFoundPage, SuitesRedirect } from '../pages/placeholders.tsx';
import { OverviewPage } from '../pages/OverviewPage.tsx';
import { RunsPage } from '../pages/RunsPage.tsx';
import { WorkersPage } from '../pages/WorkersPage.tsx';
import { RunDetailRoute } from '../runs/detail/RunDetailPage.tsx';
import { SuitesPage } from '../pages/SuitesPage.tsx';
import { CaseHistoryPage } from '../cases/CaseHistoryPage.tsx';
import { ComparePage } from '../compare/ComparePage.tsx';
import { FailureGroupPage } from '../failures/FailureGroupPage.tsx';
import { FailuresPage } from '../failures/FailuresPage.tsx';
import { PolicyPage, PolicyRedirect } from '../quality/PolicyPage.tsx';
import { QuarantinesPage } from '../quality/QuarantinesPage.tsx';
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
      { path: 'compare', element: <ComparePage /> },
      { path: 'failures', element: <FailuresPage /> },
      { path: 'failures/:groupId', element: <FailureGroupPage /> },
      { path: 'policy', element: <PolicyRedirect /> },
      { path: 'projects/:projectId/policy', element: <PolicyPage /> },
      { path: 'quarantines', element: <QuarantinesPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

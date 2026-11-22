import '@fontsource-variable/inter';
import '@fontsource-variable/jetbrains-mono';
import './styles/app.css';

import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { createQueryClient } from './api/queries.ts';
import { routes } from './app/routes.tsx';
import { applyTheme, storedTheme } from './app/theme.ts';

const root = document.getElementById('root');
if (!root) throw new Error('missing #root');

// Before the first paint, so a light-theme user never sees a dark flash.
applyTheme(storedTheme());

const queryClient = createQueryClient();
const router = createBrowserRouter(routes);

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);

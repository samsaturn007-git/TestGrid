import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { createQueryClient } from '../api/queries.ts';
import { routes } from '../app/routes.tsx';

/** The whole app (shell, routes, query client) at `path`, in a memory router. */
export function renderApp(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const client = createQueryClient();
  const user = userEvent.setup();
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { router, client, user, ...view };
}

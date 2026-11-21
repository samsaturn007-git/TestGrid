import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createQueryClient } from '../api/queries.ts';
import type { Problem } from '../api/types.ts';
import { catalogApi, json, PROJECT_ID, stubApi } from '../test/api.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { routes } from './routes.tsx';

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('App shell (PRD-05 FR-1)', () => {
  it('starts with a skip link to the main landmark, and has banner, nav and main', async () => {
    stubApi(catalogApi());
    renderAt('/');
    const user = userEvent.setup();

    await user.tab();
    const skip = screen.getByRole('link', { name: 'Skip to content' });
    expect(skip).toHaveFocus();
    expect(skip).toHaveAttribute('href', '#main');

    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main');
  });

  it('shows the project from the API and links Suites to that project', async () => {
    stubApi(catalogApi());
    renderAt('/');

    expect(await screen.findByText('Local project')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Suites' })).toHaveAttribute(
      'href',
      `/projects/${PROJECT_ID}/suites`,
    );
  });

  it('marks the current page in the nav and moves focus to main after navigating', async () => {
    stubApi(catalogApi());
    renderAt('/');
    const user = userEvent.setup();

    expect(screen.getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page');
    await user.click(screen.getByRole('link', { name: 'Runs' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Runs' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Runs' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('main')).toHaveFocus();
  });

  it('reaches every page with the keyboard alone', async () => {
    stubApi(catalogApi());
    renderAt('/');
    const user = userEvent.setup();
    await screen.findByText('Local project');

    for (const name of ['Runs', 'Suites', 'Workers', 'Overview']) {
      screen.getByRole('link', { name }).focus();
      await user.keyboard('{Enter}');
      expect(await screen.findByRole('heading', { level: 1, name })).toBeInTheDocument();
    }
  });

  it('/suites redirects to the caller project suites', async () => {
    stubApi(catalogApi());
    const router = renderAt('/suites');
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(`/projects/${PROJECT_ID}/suites`),
    );
  });

  it('shows the Problem Details title, detail and trace ID when the API fails', async () => {
    const problem: Problem = {
      type: 'https://testgrid.local/problems/internal-error',
      title: 'Internal error',
      status: 500,
      detail: 'Something went wrong on our side.',
      instance: '/api/v1/projects',
      traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    };
    const { fetchMock } = stubApi(() => json(problem, 500));
    renderAt('/suites');

    const alert = await screen.findByRole('alert', {}, { timeout: 8000 });
    expect(alert).toHaveTextContent('Internal error');
    expect(alert).toHaveTextContent('Something went wrong on our side.');
    expect(alert).toHaveTextContent(problem.traceId);
    // A 5xx is retried twice before the error shows; a 4xx would not be.
    expect(fetchMock).toHaveBeenCalledTimes(3);
  }, 10_000);

  it('does not retry a 4xx', async () => {
    const { fetchMock } = stubApi(() =>
      json(
        {
          type: 'https://testgrid.local/problems/not-found',
          title: 'Not found',
          status: 404,
          detail: 'No such project.',
          instance: '/api/v1/projects',
          traceId: '0af7651916cd43dd8448eb211c80319c',
        },
        404,
      ),
    );
    renderAt('/suites');
    expect(await screen.findByRole('alert')).toHaveTextContent('Not found');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('says the API is unreachable when the request never gets an answer', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))),
    );
    renderAt('/suites');
    expect(await screen.findByRole('alert', {}, { timeout: 8000 })).toHaveTextContent(
      'The API could not be reached',
    );
  }, 10_000);

  it('switches theme with an accessible toggle and remembers the choice', async () => {
    stubApi(catalogApi());
    renderAt('/');
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Switch to light theme' }));
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(localStorage.getItem('testgrid.theme')).toBe('light');

    await user.click(screen.getByRole('button', { name: 'Switch to dark theme' }));
    expect(document.documentElement.dataset.theme).toBe('dark');
  });

  it('answers an unknown address with a not-found page', async () => {
    stubApi(catalogApi());
    renderAt('/nope');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
  });

  it.each(['/', '/runs', '/runs/abc', `/projects/${PROJECT_ID}/suites`, '/workers', '/nope'])(
    '%s has no serious or critical axe violations',
    async (path) => {
      stubApi(catalogApi());
      renderAt(path);
      await screen.findByText('Local project');
      await expectNoSeriousA11yViolations();
    },
  );
});

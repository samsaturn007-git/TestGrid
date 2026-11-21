import { QueryClient, useQuery } from '@tanstack/react-query';
import { ApiError, apiGet } from './client.ts';
import type { PageProject } from './types.ts';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Retry only what a retry can fix; a 404 or 400 is shown at once.
        retry: (failureCount, error) =>
          failureCount < 2 && (!(error instanceof ApiError) || error.retryable),
      },
    },
  });
}

export const queryKeys = {
  projects: ['projects'] as const,
};

export function useProjects() {
  return useQuery({
    queryKey: queryKeys.projects,
    queryFn: ({ signal }) => apiGet<PageProject>('/projects', signal),
    staleTime: 5 * 60_000,
  });
}

/** The caller's project. Today CallerScope always resolves the one local project. */
export function useCurrentProject() {
  const query = useProjects();
  return { ...query, project: query.data?.items[0] ?? null };
}

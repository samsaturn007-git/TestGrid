import {
  QueryClient,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { apiSearch, type RunFilters } from '../runs/runFilters.ts';
import { ApiError, apiGet, apiPost } from './client.ts';
import type {
  Capabilities,
  CreateRunRequest,
  PageProject,
  PageRunSummary,
  PageSuite,
  RunResponse,
  SuiteDetail,
} from './types.ts';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Retry only what a retry can fix; a 404 or 400 is shown at once.
        retry: (failureCount, error) =>
          failureCount < 2 && (!(error instanceof ApiError) || error.retryable),
      },
      // A POST is never retried behind the user's back: the dialog keeps its idempotency key,
      // so the user's own retry is safe and replays the run if the first one got through.
      mutations: { retry: false },
    },
  });
}

export const queryKeys = {
  projects: ['projects'] as const,
  capabilities: ['capabilities'] as const,
  suites: (projectId: string) => ['projects', projectId, 'suites'] as const,
  suite: (suiteId: string) => ['suites', suiteId] as const,
  // Every run list (any filters) starts with runLists, so one prefix invalidates them all.
  runLists: ['runs', 'list'] as const,
  run: (runId: string) => ['runs', 'detail', runId] as const,
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

/** Deployment settings; they change only when the API restarts. */
export function useCapabilities() {
  return useQuery({
    queryKey: queryKeys.capabilities,
    queryFn: ({ signal }) => apiGet<Capabilities>('/capabilities', signal),
    staleTime: 5 * 60_000,
  });
}

/** Current suites come from the manifest, which changes only when the API restarts. */
export function useSuites(projectId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.suites(projectId ?? ''),
    queryFn: ({ signal }) =>
      apiGet<PageSuite>(`/projects/${encodeURIComponent(projectId ?? '')}/suites`, signal),
    enabled: projectId !== undefined,
    staleTime: 60_000,
  });
}

/** One suite revision with its cases. A revision never changes, so it never goes stale. */
export function useSuite(suiteId: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.suite(suiteId),
    queryFn: ({ signal }) => apiGet<SuiteDetail>(`/suites/${encodeURIComponent(suiteId)}`, signal),
    enabled,
    staleTime: Infinity,
  });
}

export function useCreateRun(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateRunRequest) =>
      apiPost<RunResponse>(`/projects/${encodeURIComponent(projectId)}/runs`, request),
    onSuccess: async (result) => {
      client.setQueryData(queryKeys.run(result.data.id), result.data);
      await client.invalidateQueries({ queryKey: queryKeys.runLists });
    },
  });
}

/**
 * The run list, one keyset page at a time ("Load more"). Filters are part of the key, so each
 * filter combination has its own cache entry and switching back to one is instant.
 */
export function useRunList(filters: RunFilters) {
  return useInfiniteQuery({
    queryKey: [...queryKeys.runLists, filters] as const,
    queryFn: ({ pageParam, signal }) =>
      apiGet<PageRunSummary>(`/runs?${apiSearch(filters, pageParam)}`, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

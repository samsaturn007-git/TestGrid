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
  RerunRequest,
  RunResponse,
  RunCounts,
  RunResults,
  RunTimeline,
  ArtifactLink,
  CaseHistoryView,
  RunComparisonView,
  SuiteDetail,
  WorkersResponse,
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
  runResults: (runId: string) => ['runs', 'detail', runId, 'results'] as const,
  runTimeline: (runId: string) => ['runs', 'detail', runId, 'timeline'] as const,
  artifactLink: (artifactId: string) => ['artifacts', artifactId, 'link'] as const,
  artifactText: (artifactId: string) => ['artifacts', artifactId, 'text'] as const,
  runCounts: (projectId: string, hours: number) => ['run-counts', projectId, hours] as const,
  workers: ['workers'] as const,
  comparison: (base: string, head: string) => ['runs', 'compare', base, head] as const,
  caseHistory: (suiteKey: string, caseKey: string) =>
    ['cases', suiteKey, caseKey, 'history'] as const,
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

const TERMINAL = new Set(['PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED']);
export const isTerminal = (status: string) => TERMINAL.has(status);

/** Fallback refresh while the event stream is down (PRD-05 FR-3). */
export const POLL_MS = 5_000;

/**
 * One run's snapshot. Live updates (useRunEvents) refetch it on each event; `poll` is the
 * fallback after the stream has failed, so the page is never left on a stale RUNNING. A
 * finished run doesn't change and is never polled.
 */
export function useRun(runId: string, poll = false) {
  return useQuery({
    queryKey: queryKeys.run(runId),
    queryFn: ({ signal }) => apiGet<RunResponse>(`/runs/${encodeURIComponent(runId)}`, signal),
    refetchInterval: (query) =>
      poll && !(query.state.data && isTerminal(query.state.data.status)) ? POLL_MS : false,
  });
}

/** Every attempt with its case results. Refetched whenever the run itself changes. */
export function useRunResults(runId: string, runVersion: string | undefined) {
  return useQuery({
    queryKey: [...queryKeys.runResults(runId), runVersion] as const,
    queryFn: ({ signal }) =>
      apiGet<RunResults>(`/runs/${encodeURIComponent(runId)}/results`, signal),
    enabled: runVersion !== undefined,
    placeholderData: (previous) => previous,
  });
}

/** Heartbeats arrive without events, so an unfinished run's timeline also refreshes on a timer. */
export const TIMELINE_REFRESH_MS = 10_000;

/** PRD-06 FR-7: events by target and attempt, heartbeats and gaps. */
export function useRunTimeline(runId: string, runVersion: string | undefined, live: boolean) {
  return useQuery({
    queryKey: [...queryKeys.runTimeline(runId), runVersion] as const,
    queryFn: ({ signal }) =>
      apiGet<RunTimeline>(`/runs/${encodeURIComponent(runId)}/timeline`, signal),
    enabled: runVersion !== undefined,
    placeholderData: (previous) => previous,
    refetchInterval: live ? TIMELINE_REFRESH_MS : false,
  });
}

/** A link lasts 5 minutes; refresh it a minute early so an image never points at a dead one. */
const LINK_STALE_MS = 4 * 60_000;

/** PRD-06 FR-4: a short-lived signed URL for one artifact's bytes. */
export function useArtifactLink(artifactId: string | null) {
  return useQuery({
    queryKey: queryKeys.artifactLink(artifactId ?? ''),
    queryFn: async () =>
      (
        await apiPost<ArtifactLink>(
          `/artifacts/${encodeURIComponent(artifactId ?? '')}/links`,
          undefined,
        )
      ).data,
    enabled: artifactId !== null,
    staleTime: LINK_STALE_MS,
    gcTime: LINK_STALE_MS,
    refetchInterval: LINK_STALE_MS,
  });
}

/** The log tail as text, read through a fresh signed link (at most 256 KB). */
export function useArtifactText(artifactId: string | null) {
  return useQuery({
    queryKey: queryKeys.artifactText(artifactId ?? ''),
    queryFn: async ({ signal }) => {
      const link = (
        await apiPost<ArtifactLink>(
          `/artifacts/${encodeURIComponent(artifactId ?? '')}/links`,
          undefined,
        )
      ).data;
      const response = await fetch(link.url, { signal });
      if (!response.ok) throw new ApiError(response.status, null);
      return response.text();
    },
    enabled: artifactId !== null,
    staleTime: Infinity,
  });
}

/** For a download button: a fresh link at the moment of the click. */
export async function freshArtifactLink(artifactId: string): Promise<ArtifactLink> {
  return (
    await apiPost<ArtifactLink>(`/artifacts/${encodeURIComponent(artifactId)}/links`, undefined)
  ).data;
}

export function useCancelRun(runId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => apiPost<RunResponse>(`/runs/${encodeURIComponent(runId)}/cancel`, undefined),
    onSuccess: async (result) => {
      client.setQueryData(queryKeys.run(runId), result.data);
      await client.invalidateQueries({ queryKey: queryKeys.runLists });
    },
  });
}

export function useRerun(runId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (request: RerunRequest) =>
      apiPost<RunResponse>(`/runs/${encodeURIComponent(runId)}/rerun`, request),
    onSuccess: async (result) => {
      client.setQueryData(queryKeys.run(result.data.id), result.data);
      // The old run now lists the rerun in rerunIds.
      await client.invalidateQueries({ queryKey: queryKeys.run(runId), exact: true });
      await client.invalidateQueries({ queryKey: queryKeys.runLists });
    },
  });
}

/**
 * The Overview and Workers pages have no event stream of their own; they refresh on a timer and
 * say when they were last updated.
 */
export const OVERVIEW_REFRESH_MS = 15_000;
export const WORKERS_REFRESH_MS = 10_000;

export function useRunCounts(projectId: string | undefined, hours = 24) {
  return useQuery({
    queryKey: queryKeys.runCounts(projectId ?? '', hours),
    queryFn: ({ signal }) =>
      apiGet<RunCounts>(
        `/projects/${encodeURIComponent(projectId ?? '')}/run-counts?hours=${hours}`,
        signal,
      ),
    enabled: projectId !== undefined,
    refetchInterval: OVERVIEW_REFRESH_MS,
  });
}

/** The newest runs, for the Overview. Shares the run-list prefix, so new runs invalidate it. */
export function useRecentRuns(limit = 8) {
  return useQuery({
    queryKey: [...queryKeys.runLists, 'recent', limit] as const,
    queryFn: ({ signal }) => apiGet<PageRunSummary>(`/runs?limit=${limit}`, signal),
    refetchInterval: OVERVIEW_REFRESH_MS,
  });
}

export function useWorkers(refreshMs = WORKERS_REFRESH_MS) {
  return useQuery({
    queryKey: queryKeys.workers,
    queryFn: ({ signal }) => apiGet<WorkersResponse>('/workers', signal),
    refetchInterval: refreshMs,
  });
}

/** PRD-07 FR-5: the last 20 results per engine and each engine's health label. */
export const CASE_HISTORY_LIMIT = 20;

export function useCaseHistory(caseKey: string, suiteKey: string | null) {
  return useQuery({
    queryKey: queryKeys.caseHistory(suiteKey ?? '', caseKey),
    queryFn: ({ signal }) =>
      apiGet<CaseHistoryView>(
        `/cases/${encodeURIComponent(caseKey)}/history?suiteKey=${encodeURIComponent(suiteKey ?? '')}&limit=${CASE_HISTORY_LIMIT}`,
        signal,
      ),
    enabled: suiteKey !== null && suiteKey !== '',
  });
}

/** PRD-07 FR-6: `base` is a run ID or `last-green`. */
export function useComparison(base: string | null, head: string | null) {
  return useQuery({
    queryKey: queryKeys.comparison(base ?? '', head ?? ''),
    queryFn: ({ signal }) =>
      apiGet<RunComparisonView>(
        `/runs/compare?base=${encodeURIComponent(base ?? '')}&head=${encodeURIComponent(head ?? '')}`,
        signal,
      ),
    enabled: Boolean(base) && Boolean(head),
  });
}

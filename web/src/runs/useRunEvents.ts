import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { queryKeys } from '../api/queries.ts';

export { POLL_MS } from '../api/queries.ts';

/**
 * How the run page is kept current (PRD-05 FR-3):
 * - `connecting`: the stream is opening or reconnecting (EventSource retries by itself).
 * - `live`: the stream is open; each event refetches the snapshot.
 * - `polling`: the stream failed twice in a row; the snapshot is refetched every 5 s, and a new
 *   stream is tried every 30 s.
 */
export type LiveMode = 'connecting' | 'live' | 'polling';

/** Contracts §5 event types, plus RESYNC. Each one means "the snapshot changed". */
export const RUN_EVENT_TYPES = [
  'RUN_CREATED',
  'RUN_CANCEL_REQUESTED',
  'RUN_FINISHED',
  'TARGET_DISPATCHED',
  'TARGET_CLAIMED',
  'TARGET_STARTED',
  'TARGET_RETRY_SCHEDULED',
  'TARGET_FINISHED',
  'ATTEMPT_ABANDONED',
  'CASE_FINISHED',
  'ARTIFACT_AVAILABLE',
  'QUALITY_EVALUATED',
] as const;
export const RESYNC = 'RESYNC';

/** "After 2 failed reconnects it falls back to polling" (FR-3). */
export const MAX_FAILED_RECONNECTS = 2;
export const RETRY_STREAM_MS = 30_000;
/**
 * A reconnect that neither opens nor fails (a proxy holding the request) also counts as failed:
 * if the stream is not back this long after a drop, the page polls instead.
 */
export const RECONNECT_TIMEOUT_MS = 10_000;
/** Events often come in bursts (a target finishing writes several); one refetch serves them. */
export const COALESCE_MS = 100;

/**
 * Keeps one run's cached snapshot current from `GET /runs/{id}/events` while `active` (the run
 * is unfinished). The stream only says *that* something changed; the page always shows the
 * snapshot from `GET /runs/{id}`, so an event can never paint a status the server doesn't have.
 * Event ids only move forward: a repeated or older id is ignored.
 */
export function useRunEvents(
  runId: string,
  active: boolean,
  onMode: (mode: LiveMode) => void,
): void {
  const client = useQueryClient();

  useEffect(() => {
    if (!active) return;
    let source: EventSource | null = null;
    let failures = 0;
    let lastId = 0;
    let coalesce: ReturnType<typeof setTimeout> | undefined;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let watchdog: ReturnType<typeof setTimeout> | undefined;

    const refetchNow = () => {
      clearTimeout(coalesce);
      coalesce = undefined;
      void client.invalidateQueries({ queryKey: queryKeys.run(runId), exact: true });
      // Results are keyed by the run's state; case results can change without it.
      void client.invalidateQueries({ queryKey: queryKeys.runResults(runId) });
    };
    const refetchSoon = () => {
      coalesce ??= setTimeout(refetchNow, COALESCE_MS);
    };

    const onEvent = (event: MessageEvent<string>) => {
      try {
        JSON.parse(event.data);
      } catch {
        // A frame we can't read may hide a change: trust the snapshot instead.
        refetchNow();
        return;
      }
      const id = Number(event.lastEventId);
      if (event.type === RESYNC) {
        if (Number.isSafeInteger(id)) lastId = Math.max(lastId, id);
        refetchNow();
        return;
      }
      if (!Number.isSafeInteger(id) || id <= lastId) return;
      lastId = id;
      refetchSoon();
    };

    const fallBack = (es: EventSource) => {
      clearTimeout(watchdog);
      watchdog = undefined;
      es.close();
      source = null;
      onMode('polling');
      retry = setTimeout(connect, RETRY_STREAM_MS);
    };

    const connect = () => {
      const es = new EventSource(`/api/v1/runs/${encodeURIComponent(runId)}/events`);
      source = es;
      es.onopen = () => {
        failures = 0;
        clearTimeout(watchdog);
        watchdog = undefined;
        onMode('live');
      };
      for (const type of [...RUN_EVENT_TYPES, RESYNC]) {
        es.addEventListener(type, onEvent as EventListener);
      }
      es.onerror = () => {
        // The first error is the drop (or a failed first connect); EventSource then retries on
        // its own, with Last-Event-ID. Each further error is a failed reconnect. A CLOSED
        // source (a non-200 answer) will not retry at all.
        failures += es.readyState === EventSource.CLOSED ? MAX_FAILED_RECONNECTS + 1 : 1;
        if (failures > MAX_FAILED_RECONNECTS) {
          fallBack(es);
        } else {
          onMode('connecting');
          watchdog ??= setTimeout(() => {
            if (source === es && es.readyState !== EventSource.OPEN) fallBack(es);
          }, RECONNECT_TIMEOUT_MS);
        }
      };
    };

    connect();
    return () => {
      clearTimeout(coalesce);
      clearTimeout(retry);
      clearTimeout(watchdog);
      source?.close();
    };
  }, [runId, active, client, onMode]);
}

-- PRD-04 T7, invariant I8 (ARCHITECTURE §5): targets that are not terminal and should have moved.
-- :dispatch_timeout_s comes from TESTGRID_DISPATCH_TIMEOUT_MS (scripts/stuck-runs.sh), the value the
-- reaper uses; the other thresholds are the reaper interval (5 s) plus margin and the suite timeout
-- plus 60 s grace. A row here means a loop is not running or nothing can take the target.
WITH latest AS (
  SELECT DISTINCT ON (a.target_id) a.target_id, a.attempt_no, a.worker_id, a.status,
         a.leased_until, a.started_at
  FROM attempts a
  ORDER BY a.target_id, a.attempt_no DESC
), targets AS (
  SELECT r.id AS run_id, r.status AS run_status, t.id AS target_id, t.target_key, t.engine,
         t.status, t.attempt_count || '/' || t.max_attempts AS attempts, t.dispatch_generation,
         t.queued_at, t.dispatched_at, t.not_before, l.worker_id, l.leased_until,
         CASE
           WHEN t.status IN ('CLAIMED', 'RUNNING') AND l.leased_until < now() - interval '15 seconds'
             THEN 'LEASE_EXPIRED_NOT_REAPED'
           WHEN t.status = 'RUNNING'
                AND l.started_at < now() - make_interval(secs => s.timeout_ms / 1000.0 + 60)
             THEN 'RUNNING_PAST_SUITE_TIMEOUT'
           WHEN t.status = 'QUEUED'
                AND t.dispatched_at < now() - make_interval(secs => :dispatch_timeout_s + 60)
             THEN 'DISPATCH_NOT_RECOVERED'
           WHEN t.status = 'QUEUED' AND NOT EXISTS (
                  SELECT 1 FROM workers w
                  WHERE w.state = 'ACTIVE' AND t.engine = ANY (w.engines)
                    AND t.executor = w.executor)
             THEN 'NO_ACTIVE_WORKER_FOR_ENGINE'
           WHEN t.status = 'QUEUED' AND t.dispatched_at IS NULL
                AND greatest(t.queued_at, coalesce(t.not_before, t.queued_at))
                    < now() - interval '10 minutes'
             THEN 'WAITING_FOR_CAPACITY'
         END AS reason
  FROM run_targets t
  JOIN runs r ON r.id = t.run_id
  JOIN suites s ON s.id = r.suite_id
  LEFT JOIN latest l ON l.target_id = t.id
  WHERE t.finished_at IS NULL
)
SELECT run_id, target_key, status, attempts, dispatch_generation AS gen, reason,
       coalesce(worker_id, '-') AS worker,
       to_char(queued_at, 'HH24:MI:SS') AS queued, to_char(dispatched_at, 'HH24:MI:SS') AS dispatched,
       to_char(leased_until, 'HH24:MI:SS') AS lease_until
FROM targets
WHERE reason IS NOT NULL
ORDER BY queued_at, target_key;

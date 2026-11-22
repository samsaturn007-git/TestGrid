-- PRD-05 T4: GET /runs/{runId} lists the reruns of a run (runs.parent_run_id from V1), so the link
-- is shown in both directions. Only reruns have a parent, so only they are indexed.
CREATE INDEX runs_parent_run_id ON runs (parent_run_id) WHERE parent_run_id IS NOT NULL;

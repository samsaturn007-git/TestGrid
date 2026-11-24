-- PRD-07 FR-4/FR-5 (contracts §3, §8.3): each case's rolling health per engine, and the index
-- behind its history.

CREATE TABLE case_health (
  project_id      uuid NOT NULL REFERENCES projects (id),
  suite_key       text NOT NULL CHECK (suite_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(suite_key) <= 64),
  case_key        text NOT NULL CHECK (case_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(case_key) <= 64),
  engine          text NOT NULL CHECK (engine IN ('chromium', 'firefox', 'webkit')),
  window_size     integer NOT NULL CHECK (window_size BETWEEN 5 AND 100),
  -- The window itself, oldest first: P passed, K passed only on a retry, F failed, T timed out.
  -- Kept so each new result updates the row in O(N) without reading history.
  recent_outcomes text NOT NULL CHECK (recent_outcomes ~ '^[PKFT]{0,100}$'),
  observed        integer NOT NULL CHECK (observed BETWEEN 0 AND 100),
  pass_count      integer NOT NULL CHECK (pass_count >= 0),
  fail_count      integer NOT NULL CHECK (fail_count >= 0),
  flaky_count     integer NOT NULL CHECK (flaky_count >= 0),
  flip_count      integer NOT NULL CHECK (flip_count >= 0),
  flip_rate       numeric(4, 3) NOT NULL CHECK (flip_rate BETWEEN 0 AND 1),
  label           text NOT NULL CHECK (label IN ('STABLE', 'FLAKY', 'FAILING', 'INSUFFICIENT_DATA')),
  reason          text NOT NULL CHECK (length(reason) BETWEEN 1 AND 300),
  computed_at     timestamptz NOT NULL DEFAULT now(),
  version         integer NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (project_id, suite_key, case_key, engine),
  CONSTRAINT case_health_counts CHECK (
    observed = length(recent_outcomes)
    AND pass_count + fail_count = observed
    AND flaky_count <= pass_count
    AND flip_count <= greatest(observed - 1, 0)
    AND observed <= window_size)
);

-- GET /cases/{caseKey}/history: a case's newest results without scanning every case result.
CREATE INDEX case_results_case_history ON case_results (case_key, created_at DESC, id DESC);

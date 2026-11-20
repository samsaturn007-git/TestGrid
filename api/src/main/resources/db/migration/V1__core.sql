-- PRD-02 FR-1: core domain tables (contracts §3). Enum spellings are contracts §2.1.
-- Invariants live in the schema as constraints so a bug in the API cannot write a
-- row that breaks them. Versions are immutable once merged: change things in V2+.

CREATE TABLE projects (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug       text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(slug) <= 64),
  name       text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  owner_id   uuid NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE suites (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id         uuid NOT NULL REFERENCES projects (id),
  suite_key          text NOT NULL CHECK (suite_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(suite_key) <= 64),
  revision           text NOT NULL CHECK (revision ~ '^[0-9a-f]{12}$'),
  display_name       text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
  enabled            boolean NOT NULL DEFAULT true,
  is_current         boolean NOT NULL DEFAULT false,
  timeout_ms         integer NOT NULL CHECK (timeout_ms BETWEEN 1000 AND 3600000),
  playwright_retries integer NOT NULL CHECK (playwright_retries BETWEEN 0 AND 3),
  allowed_engines    text[] NOT NULL CHECK (
    cardinality(allowed_engines) >= 1
    AND allowed_engines <@ ARRAY['chromium', 'firefox', 'webkit']::text[]),
  fixture_modes      text[] NOT NULL CHECK (
    fixture_modes <@ ARRAY['PASS', 'PRODUCT_DEFECT', 'AUTOMATION_FLAKE', 'SELECTOR_DRIFT', 'TIMEOUT']::text[]),
  created_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (project_id, suite_key, revision),
  -- Target of the composite FK from runs: a run's suite must belong to the run's project.
  UNIQUE (project_id, id)
);

-- Old revisions stay for history; at most one revision per suite key is current.
CREATE UNIQUE INDEX suites_one_current_per_key ON suites (project_id, suite_key) WHERE is_current;

CREATE TABLE test_cases (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  suite_id             uuid NOT NULL REFERENCES suites (id),
  case_key             text NOT NULL CHECK (case_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(case_key) <= 64),
  title                text NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  tag                  text NOT NULL CHECK (tag = '@' || case_key),
  critical             boolean NOT NULL DEFAULT false,
  expected_duration_ms integer NULL CHECK (expected_duration_ms > 0),
  created_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (suite_id, case_key)
);

CREATE TABLE runs (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id          uuid NOT NULL REFERENCES projects (id),
  suite_id            uuid NOT NULL,
  status              text NOT NULL CHECK (status IN
    ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED')),
  trigger             text NOT NULL CHECK (trigger IN ('MANUAL', 'CI', 'RERUN', 'BENCHMARK', 'CHAOS')),
  fixture_mode        text NULL CHECK (fixture_mode IN
    ('PASS', 'PRODUCT_DEFECT', 'AUTOMATION_FLAKE', 'SELECTOR_DRIFT', 'TIMEOUT')),
  fixture_seed        integer NULL CHECK (fixture_seed >= 0),
  commit_sha          text NULL CHECK (commit_sha ~ '^[0-9a-f]{7,64}$'),
  branch              text NULL CHECK (length(branch) BETWEEN 1 AND 200),
  ci_ref              text NULL CHECK (length(ci_ref) BETWEEN 1 AND 200),
  idempotency_key     text NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,200}$'),
  request_hash        bytea NOT NULL CHECK (octet_length(request_hash) = 32),
  sharding_mode       text NOT NULL DEFAULT 'NONE' CHECK (sharding_mode IN
    ('NONE', 'PLAYWRIGHT_NATIVE', 'DURATION_BALANCED')),
  shard_count         integer NOT NULL DEFAULT 1 CHECK (shard_count BETWEEN 1 AND 16),
  cancel_requested_at timestamptz NULL,
  parent_run_id       uuid NULL REFERENCES runs (id),
  queued_at           timestamptz NOT NULL DEFAULT now(),
  started_at          timestamptz NULL,
  finished_at         timestamptz NULL,
  version             integer NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  -- The idempotency guarantee: concurrent duplicates collide here (FR-5, API-004).
  UNIQUE (project_id, idempotency_key),
  CONSTRAINT runs_suite_in_project FOREIGN KEY (project_id, suite_id) REFERENCES suites (project_id, id),
  CONSTRAINT runs_seed_needs_fixture_mode CHECK (fixture_seed IS NULL OR fixture_mode IS NOT NULL),
  CONSTRAINT runs_shards_need_sharding_mode CHECK (sharding_mode <> 'NONE' OR shard_count = 1),
  CONSTRAINT runs_finished_iff_terminal CHECK (
    (finished_at IS NOT NULL) = (status IN ('PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED')))
);

-- Keyset pagination for GET /runs (FR-6, API-005).
CREATE INDEX runs_project_queued_at_id ON runs (project_id, queued_at DESC, id DESC);
CREATE INDEX runs_project_status ON runs (project_id, status);

CREATE TABLE run_targets (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id              uuid NOT NULL REFERENCES runs (id),
  target_key          text NOT NULL CHECK (target_key ~ '^[a-z]+:[a-z0-9-]+:[0-9]+$' AND length(target_key) <= 100),
  executor            text NOT NULL CHECK (executor IN ('LOCAL', 'BROWSERSTACK')),
  engine              text NOT NULL CHECK (engine IN ('chromium', 'firefox', 'webkit')),
  profile_key         text NULL CHECK (length(profile_key) BETWEEN 1 AND 100),
  shard_index         integer NOT NULL DEFAULT 0,
  shard_count         integer NOT NULL DEFAULT 1 CHECK (shard_count BETWEEN 1 AND 16),
  status              text NOT NULL CHECK (status IN
    ('QUEUED', 'CLAIMED', 'RUNNING', 'PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED')),
  attempt_count       integer NOT NULL DEFAULT 0,
  max_attempts        integer NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
  not_before          timestamptz NULL,
  dispatch_generation integer NOT NULL DEFAULT 0 CHECK (dispatch_generation >= 0),
  dispatched_at       timestamptz NULL,
  predicted_ms        integer NULL CHECK (predicted_ms >= 0),
  queued_at           timestamptz NOT NULL DEFAULT now(),
  finished_at         timestamptz NULL,
  version             integer NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  -- Also serves the required run_targets(run_id) lookup: run_id is the leading column.
  UNIQUE (run_id, target_key),
  CONSTRAINT run_targets_shard_index_in_range CHECK (shard_index >= 0 AND shard_index < shard_count),
  CONSTRAINT run_targets_attempts_in_range CHECK (attempt_count BETWEEN 0 AND max_attempts),
  CONSTRAINT run_targets_finished_iff_terminal CHECK (
    (finished_at IS NOT NULL) = (status IN ('PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED')))
);

CREATE INDEX run_targets_queued ON run_targets (status, not_before) WHERE status = 'QUEUED';

CREATE TABLE run_events (
  id             bigserial PRIMARY KEY,
  run_id         uuid NOT NULL REFERENCES runs (id),
  target_id      uuid NULL REFERENCES run_targets (id),
  -- FK to attempts is added by PRD-03, which creates that table.
  attempt_id     uuid NULL,
  type           text NOT NULL CHECK (type IN (
    'RUN_CREATED', 'RUN_CANCEL_REQUESTED', 'RUN_FINISHED',
    'TARGET_DISPATCHED', 'TARGET_CLAIMED', 'TARGET_STARTED', 'TARGET_RETRY_SCHEDULED', 'TARGET_FINISHED',
    'ATTEMPT_ABANDONED', 'CASE_FINISHED', 'ARTIFACT_AVAILABLE', 'QUALITY_EVALUATED')),
  schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
  payload        jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object'),
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX run_events_run_id_id ON run_events (run_id, id);

-- run_events is an append-only log (contracts §3); SSE replay (PRD-05) depends on it.
-- Row triggers don't fire on TRUNCATE, so test resets still work.
CREATE FUNCTION run_events_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'run_events is append-only (% rejected)', TG_OP
    USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER run_events_no_update_or_delete
  BEFORE UPDATE OR DELETE ON run_events
  FOR EACH ROW EXECUTE FUNCTION run_events_append_only();

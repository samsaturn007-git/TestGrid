-- PRD-03 FR-3/FR-4: attempts and case results (contracts §3). Enum spellings are contracts §2.1.

CREATE TABLE attempts (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  target_id             uuid NOT NULL REFERENCES run_targets (id),
  attempt_no            integer NOT NULL CHECK (attempt_no >= 1),
  worker_id             text NOT NULL CHECK (worker_id ~ '^[A-Za-z0-9._-]{1,64}$'),
  status                text NOT NULL CHECK (status IN
    ('CLAIMED', 'RUNNING', 'PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED', 'ABANDONED')),
  -- sha256 of the 32-byte lease token; the token itself is never stored.
  lease_token_hash      bytea NOT NULL CHECK (octet_length(lease_token_hash) = 32),
  leased_until          timestamptz NOT NULL,
  last_heartbeat_at     timestamptz NULL,
  claimed_at            timestamptz NOT NULL DEFAULT now(),
  started_at            timestamptz NULL,
  finished_at           timestamptz NULL,
  error_class           text NULL CHECK (error_class IN
    ('ASSERTION', 'TEST_TIMEOUT', 'SELECTOR_NOT_FOUND', 'BROWSER_LAUNCH', 'WORKER_LOST',
     'WORKER_SHUTDOWN', 'LEASE_EXPIRED', 'NETWORK', 'ARTIFACT_STORE', 'CONFIG', 'CANCELLED', 'UNKNOWN')),
  error_detail_redacted text NULL CHECK (length(error_detail_redacted) <= 2000),
  completion_key        text NULL CHECK (completion_key ~ '^[A-Za-z0-9._:-]{1,200}$'),
  version               integer NOT NULL DEFAULT 0,
  created_at            timestamptz NOT NULL DEFAULT now(),
  -- Also serves the required attempts(target_id, attempt_no DESC) lookup (backward index scan).
  UNIQUE (target_id, attempt_no),
  CONSTRAINT attempts_finished_iff_terminal CHECK (
    (finished_at IS NOT NULL) = (status NOT IN ('CLAIMED', 'RUNNING'))),
  CONSTRAINT attempts_started_when_running CHECK (status <> 'RUNNING' OR started_at IS NOT NULL),
  -- Only a worker completion sets the key; the reaper's ABANDONED never does.
  CONSTRAINT attempts_completion_key_only_when_completed CHECK (
    completion_key IS NULL OR status IN ('PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED'))
);

-- Invariant I1: at most one active attempt per target, enforced by the database.
CREATE UNIQUE INDEX attempts_one_active_per_target ON attempts (target_id)
  WHERE status IN ('CLAIMED', 'RUNNING');
-- Lease reaper scan (PRD-04) and the expired-lease operator query.
CREATE INDEX attempts_active_leased_until ON attempts (leased_until)
  WHERE status IN ('CLAIMED', 'RUNNING');

-- Invariant I2 for attempts: once terminal, a row never changes.
CREATE FUNCTION attempts_terminal_is_final() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status NOT IN ('CLAIMED', 'RUNNING') THEN
    RAISE EXCEPTION 'attempt % is terminal (%) and cannot change', OLD.id, OLD.status
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER attempts_no_update_after_terminal
  BEFORE UPDATE ON attempts
  FOR EACH ROW EXECUTE FUNCTION attempts_terminal_is_final();

CREATE TABLE case_results (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  attempt_id             uuid NOT NULL REFERENCES attempts (id),
  case_key               text NOT NULL CHECK (case_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(case_key) <= 64),
  status                 text NOT NULL CHECK (status IN ('PASSED', 'FAILED', 'TIMED_OUT', 'SKIPPED', 'FLAKY')),
  duration_ms            integer NOT NULL CHECK (duration_ms >= 0),
  retry_count            integer NOT NULL CHECK (retry_count BETWEEN 0 AND 3),
  error_class            text NULL CHECK (error_class IN
    ('ASSERTION', 'TEST_TIMEOUT', 'SELECTOR_NOT_FOUND', 'BROWSER_LAUNCH', 'WORKER_LOST',
     'WORKER_SHUTDOWN', 'LEASE_EXPIRED', 'NETWORK', 'ARTIFACT_STORE', 'CONFIG', 'CANCELLED', 'UNKNOWN')),
  error_message_redacted text NULL CHECK (length(error_message_redacted) <= 2000),
  -- FK to failure_fingerprints arrives with that table (PRD-07).
  fingerprint_id         uuid NULL,
  created_at             timestamptz NOT NULL DEFAULT now(),
  -- Also serves the required case_results(attempt_id) lookup: attempt_id is the leading column.
  UNIQUE (attempt_id, case_key)
);

-- Case results are evidence: only PRD-07's fingerprint link may be filled in later.
CREATE FUNCTION case_results_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' OR (to_jsonb(NEW) - 'fingerprint_id') IS DISTINCT FROM (to_jsonb(OLD) - 'fingerprint_id') THEN
    RAISE EXCEPTION 'case_results rows are immutable (% rejected)', TG_OP
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER case_results_no_change
  BEFORE UPDATE OR DELETE ON case_results
  FOR EACH ROW EXECUTE FUNCTION case_results_immutable();

ALTER TABLE run_events
  ADD CONSTRAINT run_events_attempt_fk FOREIGN KEY (attempt_id) REFERENCES attempts (id);

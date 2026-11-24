-- PRD-07 FR-1–FR-3 (contracts §3, §8.1; CONFLICT_REGISTER N25, N26): failure groups, their
-- rule-based category with its evidence, the human override, and the audit trail.

CREATE TABLE failure_fingerprints (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id        uuid NOT NULL REFERENCES projects (id),
  algo_version      text NOT NULL CHECK (algo_version ~ '^fp_v[0-9]{1,3}$'),
  fingerprint       text NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{16}$'),
  -- Part of the hash, so one value per group; the rules read it.
  error_class       text NOT NULL CHECK (error_class IN
    ('ASSERTION', 'TEST_TIMEOUT', 'SELECTOR_NOT_FOUND', 'BROWSER_LAUNCH', 'WORKER_LOST',
     'WORKER_SHUTDOWN', 'LEASE_EXPIRED', 'NETWORK', 'ARTIFACT_STORE', 'CONFIG', 'CANCELLED', 'UNKNOWN')),
  category          text NOT NULL CHECK (category IN
    ('PRODUCT_SUSPECTED', 'TEST_BROKEN', 'AUTOMATION_FLAKE', 'TIMEOUT', 'ENVIRONMENT', 'UNKNOWN')),
  confidence        numeric(3, 2) NOT NULL CHECK (confidence BETWEEN 0 AND 0.95),
  rule_id           text NOT NULL CHECK (rule_id ~ '^R[0-9]{1,3}$'),
  -- The classifier's own explanation (rules tried, facts, confidence arithmetic), shown as is.
  evidence          jsonb NOT NULL,
  -- The normalised message (fp_v1), already redacted before normalisation.
  sample_message    text NOT NULL CHECK (length(sample_message) <= 2000),
  first_seen_at     timestamptz NOT NULL DEFAULT now(),
  last_seen_at      timestamptz NOT NULL DEFAULT now(),
  occurrence_count  integer NOT NULL CHECK (occurrence_count >= 1),
  override_category text NULL CHECK (override_category IN
    ('PRODUCT_SUSPECTED', 'TEST_BROKEN', 'AUTOMATION_FLAKE', 'TIMEOUT', 'ENVIRONMENT', 'UNKNOWN')),
  override_reason   text NULL CHECK (length(override_reason) BETWEEN 1 AND 500),
  overridden_by     text NULL CHECK (overridden_by ~ '^[A-Za-z0-9._@-]{1,64}$'),
  overridden_at     timestamptz NULL,
  version           integer NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (project_id, algo_version, fingerprint),
  -- An override is who, why and when together, or nothing.
  CONSTRAINT failure_fingerprints_override_complete CHECK (
    (override_category IS NULL) = (override_reason IS NULL)
    AND (override_category IS NULL) = (overridden_by IS NULL)
    AND (override_category IS NULL) = (overridden_at IS NULL)),
  CONSTRAINT failure_fingerprints_seen_order CHECK (first_seen_at <= last_seen_at)
);

ALTER TABLE case_results
  ADD CONSTRAINT case_results_fingerprint_fk
  FOREIGN KEY (fingerprint_id) REFERENCES failure_fingerprints (id);
CREATE INDEX case_results_fingerprint ON case_results (fingerprint_id)
  WHERE fingerprint_id IS NOT NULL;

-- N25: an attempt-level failure (INFRA_ERROR, or TIMED_OUT without case results) has no case
-- result to carry the link. Set by the completion's own UPDATE, before the attempt is terminal.
ALTER TABLE attempts ADD COLUMN fingerprint_id uuid NULL REFERENCES failure_fingerprints (id);
CREATE INDEX attempts_fingerprint ON attempts (fingerprint_id) WHERE fingerprint_id IS NOT NULL;

-- Case results are evidence. The fingerprint link may be filled in once, never moved or removed.
CREATE OR REPLACE FUNCTION case_results_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE'
     OR (to_jsonb(NEW) - 'fingerprint_id') IS DISTINCT FROM (to_jsonb(OLD) - 'fingerprint_id')
     OR (OLD.fingerprint_id IS NOT NULL AND NEW.fingerprint_id IS DISTINCT FROM OLD.fingerprint_id) THEN
    RAISE EXCEPTION 'case_results rows are immutable (% rejected)', TG_OP
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END;
$$;

-- N26: introduced here for FR-3; PRD-08 (quarantine) and PRD-12 (tokens, real actors) add to it.
CREATE TABLE audit_events (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor             text NOT NULL CHECK (actor ~ '^[A-Za-z0-9._@-]{1,64}$'),
  action            text NOT NULL CHECK (action ~ '^[A-Z][A-Z_]{0,63}$'),
  object_type       text NOT NULL CHECK (object_type ~ '^[a-z][a-z_]{0,63}$'),
  object_id         uuid NOT NULL,
  occurred_at       timestamptz NOT NULL DEFAULT now(),
  metadata_redacted jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_object ON audit_events (object_type, object_id, occurred_at);

CREATE FUNCTION audit_events_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit_events rows are append-only (% rejected)', TG_OP
    USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER audit_events_no_change
  BEFORE UPDATE OR DELETE ON audit_events
  FOR EACH ROW EXECUTE FUNCTION audit_events_append_only();

-- PRD-08 FR-1 / FR-2 (contracts §3, §12): versioned quality policies and the evaluations made
-- with them. A PUT never edits a policy: it deactivates the current version and inserts the next
-- one, so an evaluation's policy_id always names the exact rules it was judged by.

CREATE TABLE quality_policies (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id uuid NOT NULL REFERENCES projects (id),
  version    int NOT NULL CHECK (version >= 1),
  policy     jsonb NOT NULL CHECK (jsonb_typeof(policy) = 'object'),
  active     boolean NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (project_id, version)
);
-- At most one active version per project; the PUT transaction flips the old one off first.
CREATE UNIQUE INDEX quality_policies_one_active ON quality_policies (project_id) WHERE active;

-- A version's content never changes and rows are never deleted; `active` may only go true → false.
CREATE FUNCTION quality_policies_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'quality_policies rows are never deleted'
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF NEW.id <> OLD.id OR NEW.project_id <> OLD.project_id OR NEW.version <> OLD.version
     OR NEW.policy <> OLD.policy OR NEW.created_at <> OLD.created_at
     OR (NEW.active AND NOT OLD.active) THEN
    RAISE EXCEPTION 'quality policy version % is immutable (only active true -> false)', OLD.version
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER quality_policies_immutable
  BEFORE UPDATE OR DELETE ON quality_policies
  FOR EACH ROW EXECUTE FUNCTION quality_policies_guard();

CREATE TABLE quality_evaluations (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id       uuid NOT NULL REFERENCES runs (id),
  policy_id    uuid NOT NULL REFERENCES quality_policies (id),
  outcome      text NOT NULL CHECK (outcome IN ('PASS', 'FAIL', 'INDETERMINATE')),
  rules        jsonb NOT NULL CHECK (jsonb_typeof(rules) = 'array'),
  evaluated_at timestamptz NOT NULL DEFAULT now(),
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (run_id, policy_id)
);

CREATE FUNCTION quality_evaluations_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'quality_evaluations rows are append-only (% rejected)', TG_OP
    USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER quality_evaluations_no_change
  BEFORE UPDATE OR DELETE ON quality_evaluations
  FOR EACH ROW EXECUTE FUNCTION quality_evaluations_append_only();

-- The evaluation sweep (CONFLICT_REGISTER N35) looks for recently finished runs with no evaluation.
CREATE INDEX runs_finished_at ON runs (finished_at) WHERE finished_at IS NOT NULL;

-- Version 1 of the local project's policy: contracts §12 with chromium as the only required
-- target, because a run may target any subset of the allowed engines.
INSERT INTO quality_policies (project_id, version, policy, active)
VALUES (
  '00000000-0000-4000-8000-000000000001',
  1,
  '{"requiredTargets": ["local:chromium"], "failOnCriticalCase": true, "maxFailedCases": 0,
    "failOnInfraError": true, "maxFlakyRate": 0.2,
    "durationRegression": {"enabled": true, "maxRatio": 1.5, "minDeltaMs": 500, "blocking": false},
    "quarantine": {"honor": true, "maxActive": 5, "autoMode": "SUGGEST"}}'::jsonb,
  true
);

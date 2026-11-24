-- PRD-08 FR-3 (contracts §3, §12): quarantines. A quarantine excuses a case's failures from the
-- gate for at most 30 days; it still shows in every report as QUARANTINED_FAIL. Whether one is in
-- force is decided at read time from the database clock (revoked_at IS NULL AND expires_at > now()),
-- so expiry needs no job to take effect. expiry_recorded_at only marks that the expiry pass has
-- written its audit event.

CREATE TABLE quarantines (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id         uuid NOT NULL REFERENCES projects (id),
  case_key           text NOT NULL CHECK (case_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND char_length(case_key) <= 64),
  engine             text NULL CHECK (engine IN ('chromium', 'firefox', 'webkit')),
  reason             text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
  source             text NOT NULL CHECK (source IN ('MANUAL', 'SUGGESTED', 'AUTO')),
  created_by         text NOT NULL CHECK (created_by ~ '^[A-Za-z0-9._@-]{1,64}$'),
  created_at         timestamptz NOT NULL DEFAULT now(),
  expires_at         timestamptz NOT NULL,
  revoked_at         timestamptz NULL,
  expiry_recorded_at timestamptz NULL,
  version            int NOT NULL DEFAULT 1,
  CHECK (expires_at > created_at AND expires_at <= created_at + interval '30 days'),
  CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

-- The evaluator and the cap read a project's unrevoked rows; the expiry pass reads the unrecorded.
CREATE INDEX quarantines_open ON quarantines (project_id, case_key) WHERE revoked_at IS NULL;
CREATE INDEX quarantines_expiry ON quarantines (expires_at)
  WHERE revoked_at IS NULL AND expiry_recorded_at IS NULL;

-- Content is immutable and rows are never deleted. revoked_at and expiry_recorded_at may each be
-- set once; version moves with them.
CREATE FUNCTION quarantines_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'quarantines rows are never deleted' USING ERRCODE = 'restrict_violation';
  END IF;
  IF NEW.id <> OLD.id OR NEW.project_id <> OLD.project_id OR NEW.case_key <> OLD.case_key
     OR NEW.engine IS DISTINCT FROM OLD.engine OR NEW.reason <> OLD.reason
     OR NEW.source <> OLD.source OR NEW.created_by <> OLD.created_by
     OR NEW.created_at <> OLD.created_at OR NEW.expires_at <> OLD.expires_at
     OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at)
     OR (OLD.expiry_recorded_at IS NOT NULL
         AND NEW.expiry_recorded_at IS DISTINCT FROM OLD.expiry_recorded_at) THEN
    RAISE EXCEPTION 'quarantine % is immutable except for a one-time revoke or expiry record', OLD.id
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER quarantines_immutable
  BEFORE UPDATE OR DELETE ON quarantines
  FOR EACH ROW EXECUTE FUNCTION quarantines_guard();

-- PRD-06 FR-7 (CONFLICT_REGISTER N23): what the attempt timeline needs that PRD-03 didn't keep.

-- Every accepted heartbeat, so a gap in the middle of an attempt is visible, not only the last
-- one (attempts.last_heartbeat_at). Written in the heartbeat's own transaction, database clock.
CREATE TABLE attempt_heartbeats (
  id         bigserial PRIMARY KEY,
  attempt_id uuid NOT NULL REFERENCES attempts (id),
  beat_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX attempt_heartbeats_attempt ON attempt_heartbeats (attempt_id, beat_at);

-- Evidence, like run_events: rows are never changed or removed.
CREATE FUNCTION attempt_heartbeats_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'attempt_heartbeats rows are append-only (% rejected)', TG_OP
    USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER attempt_heartbeats_no_change
  BEFORE UPDATE OR DELETE ON attempt_heartbeats
  FOR EACH ROW EXECUTE FUNCTION attempt_heartbeats_append_only();

-- When the case started, in ms after the attempt's first case started (Playwright's own
-- clock, so offsets within one attempt are consistent). NULL when the worker didn't report it.
ALTER TABLE case_results
  ADD COLUMN start_offset_ms integer NULL CHECK (start_offset_ms >= 0);

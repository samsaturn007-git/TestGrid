-- PRD-05 FR-4: every new run event wakes the SSE streams of its run. pg_notify inside the
-- inserting transaction is delivered only when that transaction commits, so a stream never sees
-- an event that is later rolled back. The payload is a hint ("<run_id>:<event id>"); streams
-- always read the events themselves from run_events, so a lost notification only delays them
-- until the next ping sweep.
CREATE FUNCTION run_events_notify() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_notify('run_events', NEW.run_id::text || ':' || NEW.id::text);
  RETURN NULL;
END;
$$;

CREATE TRIGGER run_events_notify AFTER INSERT ON run_events
  FOR EACH ROW EXECUTE FUNCTION run_events_notify();

-- PRD-04 FR-1: transactional outbox, worker registry and scheduler settings (contracts §3).
-- run_targets.dispatch_generation, dispatched_at and not_before already exist from V1
-- (CONFLICT_REGISTER N12), so this migration only adds what the dispatcher needs on top.

-- One row per message the API has promised to publish. The dispatcher inserts it in the same
-- transaction as the state change; the publisher only fills in the delivery columns.
CREATE TABLE outbox_events (
  id               bigserial PRIMARY KEY,
  aggregate_type   text NOT NULL CHECK (aggregate_type IN ('run_target')),
  aggregate_id     uuid NOT NULL,
  event_type       text NOT NULL CHECK (event_type IN ('TARGET_DISPATCHED')),
  schema_version   integer NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
  payload          jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  traceparent      text NULL CHECK (traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'),
  published_at     timestamptz NULL,
  publish_attempts integer NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
  -- Backoff after a nack or a confirm timeout: the publisher skips the row until then.
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  last_error       text NULL CHECK (length(last_error) <= 2000),
  created_at       timestamptz NOT NULL DEFAULT now()
);

-- The publisher's scan: unpublished rows in id order. Published rows drop out of the index.
CREATE INDEX outbox_events_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- What was promised never changes; only the delivery bookkeeping does, and a row that has
-- been published stays published (invariant I7 relies on it).
CREATE FUNCTION outbox_events_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'outbox_events rows are kept (DELETE rejected)'
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF (NEW.aggregate_type, NEW.aggregate_id, NEW.event_type, NEW.schema_version, NEW.payload,
      NEW.traceparent, NEW.created_at)
     IS DISTINCT FROM
     (OLD.aggregate_type, OLD.aggregate_id, OLD.event_type, OLD.schema_version, OLD.payload,
      OLD.traceparent, OLD.created_at) THEN
    RAISE EXCEPTION 'outbox event % content is immutable', OLD.id
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
    RAISE EXCEPTION 'outbox event % is already published', OLD.id
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER outbox_events_guarded
  BEFORE UPDATE OR DELETE ON outbox_events
  FOR EACH ROW EXECUTE FUNCTION outbox_events_guard();

-- Worker registry (FR-7). `name` is what attempts.worker_id records; a restarted container
-- registers again under the same name and gets a new row, so only live rows must be unique.
CREATE TABLE workers (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name         text NOT NULL CHECK (name ~ '^[A-Za-z0-9._-]{1,64}$'),
  version      text NOT NULL CHECK (length(version) BETWEEN 1 AND 64),
  executor     text NOT NULL CHECK (executor IN ('LOCAL', 'BROWSERSTACK')),
  engines      text[] NOT NULL CHECK (
    cardinality(engines) >= 1
    AND engines <@ ARRAY['chromium', 'firefox', 'webkit']::text[]),
  slots        integer NOT NULL CHECK (slots BETWEEN 1 AND 16),
  state        text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'DRAINING', 'LOST')),
  started_at   timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX workers_one_live_per_name ON workers (name) WHERE state <> 'LOST';

-- Single row (contracts §3): the one source for the cap, retries and lease TTL (CONFLICT_REGISTER
-- N15). The API seeds it from properties on first start and never overwrites it afterwards.
CREATE TABLE scheduler_settings (
  id                  boolean PRIMARY KEY DEFAULT true CHECK (id),
  global_max_sessions integer NOT NULL CHECK (global_max_sessions BETWEEN 1 AND 64),
  -- LPT_AGING joins this list in PRD-11.
  policy              text NOT NULL DEFAULT 'FIFO' CHECK (policy IN ('FIFO')),
  aging_weight        double precision NOT NULL DEFAULT 0 CHECK (aging_weight >= 0),
  max_attempts        integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
  lease_ttl_s         integer NOT NULL CHECK (lease_ttl_s BETWEEN 5 AND 3600),
  version             integer NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);

-- The dispatcher's FIFO pick: queued, not yet handed to the broker, oldest first.
CREATE INDEX run_targets_dispatchable ON run_targets (queued_at, id)
  WHERE status = 'QUEUED' AND dispatched_at IS NULL;

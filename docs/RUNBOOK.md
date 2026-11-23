# Operator runbook: dispatch, leases and recovery (PRD-04)

What the system promises when things fail, the timings behind those promises, and what to do when a run looks stuck or the dead-letter queue fills. Every command here was run against the local Compose stack (`docs/evidence/PRD-04.md`). Vocabulary: contracts §2 and §6. Design: ARCHITECTURE §3–§5.

## 1. Failure guarantees

**The model in one paragraph.** The database decides; the broker only delivers (ADR-004). A target is handed to RabbitMQ through the transactional outbox, so a committed dispatch is never lost and may be delivered more than once (at-least-once). Every claim names the target **and** its `dispatchGeneration`, and the claim transaction checks both, so a duplicate, late or replayed message can never start a second attempt. A worker holds an attempt only while its lease is renewed; when the lease runs out the reaper ends the attempt as `ABANDONED` and decides what happens next. Terminal states never change.

**What holds** (each row is a test that ran 5 times, `api/src/test/java/dev/testgrid/api/crash/`, or a live drill):

| Failure | What happens | Bound | Proof |
|---|---|---|---|
| The same message is delivered several times | One claim wins; the others get `409 stale-dispatch` and are acked. One attempt, one result. | — | `DuplicateMessageTest` (AC-2) |
| The API dies after the broker confirmed a publish, before the outbox row was marked | The row is still unpublished; it is published again with the same `messageId` and generation; the copy is refused by the claim. | next outbox poll (500 ms) | `PublishCrashTest`, `OutboxPublisherTest` (AC-3) |
| The worker dies after `complete`, before the ack | The broker redelivers; the target is terminal, so the claim answers `409` and the restarted worker acks. No second attempt. | — | `AckCrashTest`, `make crash-check` (AC-3) |
| The worker dies after the claim (killed, OOM, network gone) | The lease runs out; the attempt becomes `ABANDONED` (`LEASE_EXPIRED`) and is kept; the target is retried after a backoff with a new generation, or ends `INFRA_ERROR` when `max_attempts` is spent. The redelivered old message gets `409`. | `max_attempts × (lease TTL + max backoff)`; one retry ≈ TTL + 5 s reaper + backoff | `WorkerKillTest`, `make kill-check`, `make crash-check` (AC-4) |
| RabbitMQ restarts mid-run | Running attempts keep their leases (the database holds them); their acks are lost, so the broker redelivers and the claim answers `409`. Dispatches whose publish failed stay in the outbox and go out when the broker is back. | outbox backoff, max 30 s | `BrokerRestartTest` (AC-5) |
| A message can never be processed (every consumer dies before claiming it, or it doesn't parse) | After 1 + 5 deliveries the quorum queue dead-letters it to `tg.targets.dead`. After the dispatch timeout the reaper sends a new generation; after `max_attempts` unclaimed dispatches in total the target ends `INFRA_ERROR` (`WORKER_LOST`). | `max_attempts × dispatch timeout` | `DeadLetterTest`, `ReaperTest` (AC-6) |
| A cancel races a completion or a claim | Whichever transaction commits first wins; the target gets exactly one terminal state and both requests are in `run_events`. | — | `RunCancelTest`, `CancelRaceTest` (AC-7) |
| More targets than the cap | The dispatcher never releases more than `global_max_sessions − active − dispatched_unclaimed`; the claim re-checks under the same lock. | — | `CapTest`, `ClaimConcurrencyTest` (AC-1, I5) |
| A worker gets `SIGTERM` | It drains (registry `DRAINING`), stops consuming, gives running attempts 20 s, then reports `INFRA_ERROR` / `WORKER_SHUTDOWN`; messages it had not started go back to the queue. | 20 s + 10 s kill grace | `make sigterm-check` |

**What is not guaranteed**

- **Exactly-once execution.** A worker that is alive but cannot reach the API for longer than the lease TTL loses its attempt; the retry may run while the old browser is still going. The old worker's completion is then refused (`410 lease-expired`), so *results* are never duplicated, but the *work* can be.
- **Strict FIFO.** Retries wait out their backoff; a message parked in the retry queue or in a busy worker's prefetch comes back later than younger targets.
- **Dispatch latency.** At least one dispatcher tick (250 ms) plus publish; measured, not promised (evidence).
- **Precise worker liveness.** A worker becomes `LOST` only after 3 × lease TTL (90 s) of silence; a drained worker that exited shows `DRAINING` until then. Claims and leases don't depend on the registry.
- **Alerts.** The `testgrid_dlq_depth` metric and its alert arrive with PRD-12; until then use `make dlq` and `make stuck-runs`.
- **Dead-lettering is at-most-once.** If the broker fails at the exact moment it moves a message to `tg.targets.dead` or back from the retry queue, the message can be lost; the dispatch timeout recovers the target (CONFLICT_REGISTER N17).

## 2. Timing defaults

The cap, retries and lease TTL live in the single `scheduler_settings` row: the database is the only source once the row exists (CONFLICT_REGISTER N15). Everything else is an API or worker property read at startup. Compose forwards the four reaper variables (`TESTGRID_REAPER_INTERVAL_MS`, `TESTGRID_RETRY_BACKOFF_*`, `TESTGRID_DISPATCH_TIMEOUT_MS`) and `TESTGRID_WORKER_SLOTS` from `.env`; the other settings need an `environment` entry in `infra/compose/compose.yaml`.

| Setting | Default | Where | How to change |
|---|---|---|---|
| Global browser cap | 3 | `scheduler_settings.global_max_sessions` | SQL (below); next dispatcher tick |
| Max attempts | 3 | `scheduler_settings.max_attempts`, copied onto each target at run creation | SQL; new runs only |
| Lease TTL | 30 s | `scheduler_settings.lease_ttl_s` (5–3600) | SQL; new leases and the next heartbeat. Must stay longer than the heartbeat interval |
| Heartbeat interval | 10 s | `testgrid.internal.heartbeat-interval-seconds` (API, sent to the worker; env `TESTGRID_INTERNAL_HEARTBEATINTERVALSECONDS` by Spring's relaxed binding) | API restart |
| Dispatcher tick | 250 ms | `testgrid.dispatch.tick-ms` | API restart |
| Outbox poll / batch | 500 ms / 100 | `testgrid.dispatch.outbox-poll-ms`, `outbox-batch-size` | API restart |
| Publish confirm timeout | 5 s | `testgrid.dispatch.confirm-timeout-ms` | API restart |
| Publish retry backoff | 1 s × 2ⁿ⁻¹, max 30 s | `testgrid.dispatch.publish-backoff-*` | API restart |
| Reaper interval | 5 s | `TESTGRID_REAPER_INTERVAL_MS` | API restart |
| Retry backoff (attempts) | 2 s × 2ⁿ⁻¹, max 60 s, ±20 % | `TESTGRID_RETRY_BACKOFF_BASE_MS`, `TESTGRID_RETRY_BACKOFF_MAX_MS` | API restart |
| Dispatch timeout (lost message) | 5 min | `TESTGRID_DISPATCH_TIMEOUT_MS` | API restart. Keep it longer than the longest legitimate queue wait: a message still waiting (repeated `429`s after the cap was lowered, or a busy worker's prefetch) is redispatched after it, and each redispatch counts toward the `WORKER_LOST` limit. `make stuck-runs` reads the same value |
| Worker LOST after | 3 × lease TTL | fixed (`WorkerRegistry.LOST_AFTER_TTLS`) | code |
| Delivery limit | 5 redeliveries | queue argument `x-delivery-limit` | fixed: RabbitMQ refuses to change queue arguments (contracts §9) |
| Retry-queue TTL (`429`, API unreachable) | 1 s × 2ⁿ⁻¹, max 30 s, ±20 % | worker (`dispatch-message.ts`) | code |
| Worker slots / prefetch | 1 | `TESTGRID_WORKER_SLOTS` (1–16) | worker restart |
| Worker shutdown grace | 20 s | `TESTGRID_SHUTDOWN_GRACE_MS` | worker restart |

```bash
docker compose -f infra/compose/compose.yaml --env-file .env exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "UPDATE scheduler_settings SET global_max_sessions = 2, updated_at = now(), version = version + 1"'
```

`PUT /api/v1/scheduler` replaces this SQL in PRD-11.

## 3. A run looks stuck

```bash
make stuck-runs
```

Read-only (`scripts/sql/stuck-runs.sql`, invariant I8). It lists non-terminal targets that should have moved, with a reason:

| Reason | Meaning | What to do |
|---|---|---|
| `NO_ACTIVE_WORKER_FOR_ENGINE` | No `ACTIVE` worker serves this engine and executor. | `curl -s http://127.0.0.1:8080/api/v1/workers`; start or scale workers (`docker compose … up -d --scale worker=2`), or check `TESTGRID_WORKER_ENGINES`. The target runs as soon as one registers. A worker killed without draining (`kill -9`, OOM) still counts as `ACTIVE` until it has been silent for 3 × lease TTL (90 s), so this reason can be missing for that long (seen live, evidence `prd-04-runbook-tools.txt`). |
| `LEASE_EXPIRED_NOT_REAPED` | The lease ran out more than 15 s ago and the reaper hasn't acted. | The API's loops are not running: check the API log for `dispatch loops started` and `reaper pass failed`, and that `TESTGRID_DISPATCH_ENABLED` is not `false`. |
| `DISPATCH_NOT_RECOVERED` | Dispatched over 6 min ago, never claimed, not recovered. | Same as above (the reaper owns this). Then `make dlq`: its message is probably there. |
| `RUNNING_PAST_SUITE_TIMEOUT` | The attempt still heartbeats past the suite timeout + 60 s. | The worker is alive but its Playwright run is not ending: `docker compose … logs worker`; cancel the run (§6) or restart that worker (its lease then expires and the reaper retries). |
| `WAITING_FOR_CAPACITY` | Not dispatched for 10 min: the cap is full of other work. | Not a fault. Check `GET /api/v1/workers` (`activeSessions`, `dispatchedUnclaimed`) and the cap (§2). |

The run and attempt history for any target: `GET /api/v1/runs/{runId}/results`, and every transition in order: `SELECT type, payload FROM run_events WHERE run_id = '…' ORDER BY id`.

## 4. The dead-letter queue

A message lands in `tg.targets.dead` when the quorum queue's delivery limit is reached (consumers kept dying before acking it), or when a worker rejects it as unparseable or malformed (`400`). The **target is not lost**: it stays `QUEUED` and dispatched until the reaper's dispatch timeout, then gets a new generation (§1).

```bash
make dlq
```

Lists each dead letter with the reason RabbitMQ recorded and what the database says now. It reads every message and puts it back with `basic.nack(requeue)`, which on RabbitMQ 4.4 doesn't count toward the delivery limit. The broker's own queue counters lag a few seconds, so the count comes from what the tool read.

- `claimable: a replay runs it now`: the message is still the target's current dispatch (within the dispatch timeout).
- `stale: the reaper already sent generation N` or `stale: target is …`: replaying it only produces a `409` and an ack.

```bash
make dlq-replay
```

Republishes every dead letter to the engine queue it came from (header `x-first-death-queue`), body and properties unchanged apart from the broker's `x-death` headers, waits for the publisher confirm, then acks the dead letter. **Safe to run at any time:** a stale message is refused by the claim. Fix the cause first (a worker image that crashes on every message will dead-letter it again after 6 deliveries). Unparseable messages are skipped and left in place.

```bash
CONFIRM=yes bash scripts/dlq.sh purge
```

Drops every dead letter. Safe once `make dlq` shows them all stale, since their targets were recovered or finished.

Both tools run in a one-off container from the worker image (`docker compose run --rm`), so they work while the worker service is stopped. They need no published port and no new credential.

## 5. Workers

- **Registry:** `GET /api/v1/workers` lists `ACTIVE`, `DRAINING` and recently `LOST` workers with their slots and active attempts, plus `activeSessions`, `dispatchedUnclaimed` and the cap.
- **Scale:** `docker compose -f infra/compose/compose.yaml --env-file .env up -d --scale worker=2`. Each container registers as `worker-<hostname>`. With the default budget, keep N ≤ 3 (docs/VERSIONS.md §3).
- **Drain one:** `docker compose … stop` (or `docker stop <container>`) sends `SIGTERM`: the worker drains, finishes or stops its attempt within 20 s, and exits.
- **A restarted container** registers again under the same name; its old row becomes `LOST`.

## 6. Cancel a run

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/runs/<runId>/cancel
```

`202`: queued targets are `CANCELLED` at once; running ones stop on their next heartbeat (≤ 10 s) and finish `CANCELLED`, unless their completion commits first (then they keep their result). `200`: the run was already terminal and nothing changed; the request is still recorded as `RUN_CANCEL_REQUESTED {accepted: false}`.

## 7. Drills

| Command | What it proves | Time |
|---|---|---|
| `make kill-check` | `kill -9` the worker mid-run: attempt 1 `ABANDONED` and kept, attempt 2 claimed within 45 s and run to its real result | ~1.5 min |
| `make crash-check` | Real worker with `TG_FAULT=after_complete_before_ack` (one attempt, redelivery `409`) and `after_claim` (attempt 1 `ABANDONED`, attempt 2 `PASSED`) | ~1.5 min |
| `make sigterm-check` | `SIGTERM` mid-run: grace period, `INFRA_ERROR` / `WORKER_SHUTDOWN`, registry `DRAINING` | ~45 s |
| `make restart-check` | A run survives an API restart unchanged | ~30 s |
| `make scan-canary` | PRD-06 AC-4: a fresh canary secret in the worker, the API and every test page is absent from API responses, events, database rows, stored artifacts (traces unzipped) and logs; the masked log line and the withheld trace show it really was planted | ~1.5 min |

The canary (`TESTGRID_CANARY_SECRET`) and fault points are honoured only with `TESTGRID_PROFILE=local`: `TG_FAULT=after_publish_before_mark` (API) and `TG_FAULT_WORKER=after_claim | after_complete_before_ack` (worker).

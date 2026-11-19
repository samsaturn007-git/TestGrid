# PRD-04 — RabbitMQ, transactional outbox, dispatcher, bounded workers and lease recovery

**Priority:** P0 | **Estimated effort:** 20–30 h | **Dependencies:** PRD-03 | **Gate:** G2 | **Status:** not started

## Objective
Replace single-worker polling with durable, at-least-once dispatch to N workers under a global browser cap. The system must recover from crashed workers, duplicate messages and broker restarts without ever recording contradictory results.

## User stories
- As a developer, I submit 20 targets with a cap of 3 and watch them drain without ever going over 3 browser sessions.
- As an operator, when a worker dies, its target retries or ends as `INFRA_ERROR` within a bounded time, and every attempt stays visible.
- As a user, I can cancel a run, and a cancel that races with completion resolves to exactly one terminal state.

## Interview signal
Transactional outbox, at-least-once versus exactly-once, fencing with generations, retry queues with TTL and dead-lettering, advisory locks, reaper design, cancellation races.

## Functional requirements
1. **FR-1 Migrations:** `outbox_events`, `workers`, `scheduler_settings`; `run_targets.dispatch_generation`, `dispatched_at`, `not_before`.
2. **FR-2 Dispatcher** (ARCHITECTURE §3)
   - FIFO policy under `pg_advisory_xact_lock`.
   - Releases at most `global_max_sessions − active − dispatched_unclaimed` targets per tick.
   - Writes `TARGET_DISPATCHED` to both `outbox_events` and `run_events` in the same transaction.
3. **FR-3 Outbox publisher**
   - Batches of unpublished rows using `FOR UPDATE SKIP LOCKED`.
   - Publishes with publisher confirms, then marks the row published.
   - On a nack or timeout, retries with backoff and increments `publish_attempts`.
   - A crash between publish and mark is safe because a duplicate message is harmless.
4. **FR-4 Topology:** exactly as in contracts §9, declared at startup by the API and verified by an integration test.
5. **FR-5 Consumer** (worker)
   - Manual ack with `prefetch = slots`.
   - Claims by `targetId + dispatchGeneration`.
   - A `409` (terminal or stale) is acked and logged. A `429` goes to the retry queue.
   - The worker acks only after `complete` succeeds.
   - Redeliveries are capped by quorum-queue delivery limits; after that the message is dead-lettered.
6. **FR-6 Reaper:** every 5 s it expires leases, marks the attempt `ABANDONED`, and either requeues with exponential backoff and jitter or sets `INFRA_ERROR` once `max_attempts` is reached. It writes `ATTEMPT_ABANDONED` and `TARGET_RETRY_SCHEDULED` events.
7. **FR-7 Worker registry**
   - Register on start; heartbeats update `last_seen_at`; state becomes `LOST` after 3 × TTL.
   - `SIGTERM` puts the worker into `DRAINING`.
   - `GET /api/v1/workers` shows the registry.
8. **FR-8 Cancellation:** `POST /runs/{id}/cancel`
   - `QUEUED` targets become `CANCELLED` immediately.
   - Active attempts get `cancelRequested` on their next heartbeat; the worker kills Playwright and completes with `CANCELLED`.
   - If completion and cancel race, whichever transaction commits first wins. An already-terminal run returns `200` unchanged.
9. **FR-9 Trace context:** `traceparent` is stored on the outbox row and copied into the AMQP headers; the worker continues the trace in its logs.
10. **FR-10 Scale:** `docker compose up --scale worker=N` works; workers have no fixed identities.

## Tasks (in order)
- [ ] **T1** — Add migrations for outbox, worker registry, scheduler settings and dispatch columns.
- [ ] **T2** — Implement capacity-aware FIFO dispatcher and outbox publisher with publisher confirms.
- [ ] **T3** — Declare topology; implement consumer manual ack, retry queue and DLQ policy.
- [ ] **T4** — Implement reaper, bounded retries with backoff/jitter and stale-generation rejection.
- [ ] **T5** — Implement worker registry/draining and run cancellation with race handling.
- [ ] **T6** — Create Testcontainers integration tests: cap, duplicate message, worker kill, broker restart, ack-after-complete crash, cancel race.
- [ ] **T7** — Document failure guarantees, operator runbook (stuck runs, DLQ replay) and timing defaults.

## Acceptance criteria
- [ ] **AC-1:** 20 targets with cap 3: sampling active sessions every 200 ms never shows more than 3 (Q-001).
- [ ] **AC-2:** Publishing the same message twice never produces two valid active leases or conflicting terminal states (Q-002).
- [ ] **AC-3:** A crash after publish and before mark, and a crash after complete and before ack, both leave a consistent final DB state (Q-003, Q-008).
- [ ] **AC-4:** A killed worker's target is retried or ends `INFRA_ERROR` within `max_attempts × (TTL + backoff)`; the old attempt is `ABANDONED` and kept (Q-004).
- [ ] **AC-5:** Restarting the broker mid-run loses no committed target; the run reaches a terminal state (Q-006).
- [ ] **AC-6:** Exhausted retries show `INFRA_ERROR` and dead-letter-queue depth above 0 (Q-009).
- [ ] **AC-7:** A cancel racing with completion yields exactly one terminal target state and an audit trail of both requests (Q-007).

## Verification
- Testcontainers with Postgres and RabbitMQ. Failure points are injected through test-only hooks: `TG_FAULT=after_publish_before_mark`, `after_claim`, `after_complete_before_ack`.
- Repeat each crash test 5 times and record pass counts.

## Demo moment
Scale to 3 workers, queue 20 targets, `docker kill` one worker. The attempt history shows ABANDONED → retry → PASSED.

## Risks
- Races around claim and complete.
- Retry storms (bounded by backoff and the cap).
- A hot loop on capacity rejects (the retry queue's TTL prevents it).
- Confusing message delivery with test execution.

## Out of scope
Kubernetes, autoscaling, new dashboard pages, smart scheduling (PRD-11).

## Handoff and definition of done
- Implement **PRD-04 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T7 with tests, then write `docs/evidence/PRD-04.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain the outbox, generations and ack ordering without AI;
  - the tracker is updated only after human review.

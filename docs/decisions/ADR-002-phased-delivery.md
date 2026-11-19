# ADR-002: Phased delivery — single polling worker first, then RabbitMQ with a transactional outbox

**Date:** 2026-11-19 | **Status:** Accepted (developer sign-off 2026-11-19, recorded in `docs/evidence/PRD-00.md`)

## Context
- A solo, part-time developer must reach a runnable end-to-end path (G1) early, or the project risks stalling in infrastructure.
- The eventual system must survive worker crashes, duplicate deliveries and broker restarts (I1, I2, I7, I8), with at-least-once delivery and DB-exactly-once terminal transitions.
- Adding a broker before the state machine is proven multiplies the number of moving parts while debugging the basics.

## Options considered
- **A. Stage A: one worker polling `POST /internal/v1/claims/next` with `FOR UPDATE SKIP LOCKED`; Stage B (PRD-04): RabbitMQ with a transactional outbox (chosen).**
  - Strengths: the lease protocol, attempts table, completion idempotency and reaper are real from PRD-03, so the broker in PRD-04 only changes *transport*, not semantics. Each stage is independently demoable.
  - Weaknesses: the claim path changes once (claim-next → claim-by-target-ID with dispatch generation); two code paths exist briefly.
  - Ops cost: Stage A needs only PostgreSQL; Stage B adds one RabbitMQ container behind the `queue` Compose profile.
- **B. RabbitMQ from day one.**
  - Rejected because: it front-loads topology, acks, DLQ and retry-queue work before a single run has produced results, delaying G1 by roughly a PRD, and mixes "is my state machine wrong?" with "is my broker config wrong?" while debugging.
- **C. Kafka.**
  - Rejected because: log-based partitions suit high-throughput streams, not a short work queue with per-message ack, TTL retry and dead-lettering; it is heavier to run locally (memory, KRaft config) on a 16 GB machine, and per-message redelivery semantics would have to be rebuilt on top of offsets.
- **D. Redis Streams.**
  - Rejected because: it adds a second stateful store whose persistence (AOF/RDB) must be tuned to avoid losing acknowledged-but-unpersisted entries; consumer-group pending-entry handling duplicates what the lease/reaper already does; and it does not demonstrate the outbox pattern the portfolio targets.
- **E. DB-only polling forever.**
  - Strengths: simplest; `SKIP LOCKED` is a legitimate queue.
  - Rejected because: it never demonstrates at-least-once broker delivery, publisher confirms, DLQ or the outbox pattern, which are the distributed-systems signals the MASTER PRD lists. Polling load also grows with worker count.

## Decision
- **PRD-03:** a single Node worker pulls work with `claims/next`. The claim query uses `FOR UPDATE SKIP LOCKED`; leases, heartbeats, completion keys and the partial unique index on active attempts are real.
- **PRD-04:** run creation writes targets and `run_events` in one transaction; the dispatcher writes `outbox_events` in the same transaction that marks a target dispatched; a publisher sends them to RabbitMQ (quorum queues, publisher confirms, manual ack, TTL retry queue, DLQ). Workers claim by target ID + dispatch generation.

**What this does NOT claim:**
- Not exactly-once *execution*: a browser test may run twice after a crash. The guarantee is exactly-once *terminal DB transition* per attempt and at most one active attempt per target.
- Not "zero lost jobs under every failure" (a MASTER non-goal); the guarantee is bounded by I8 or visible in the `stuck_runs` query.
- Not that RabbitMQ is the best broker in general — only that it fits per-message ack/retry/DLQ semantics at this scale.

## Consequences
- **Benefits:** G1 arrives after PRD-03 with real semantics; PRD-04 is a focused, testable transport swap.
- **Risks:** the claim-next endpoint must be retired or kept consistent after PRD-04 (decide and record in PRD-04 evidence); outbox publisher lag must be measured (`testgrid_outbox_unpublished`, PRD-12).
- **Invariants:** I1, I2, I3, I7, I8 hold in both stages.
- **Rollback:** if the broker misbehaves, Stage A polling still works because the DB remains the source of truth.
- **Testing requirements:** Q-002 (duplicate delivery), Q-003 (crash between publish and outbox mark), Q-006 (broker restart), Q-008 (crash after complete, before ack).

## Verification
- PRD-03: E2E-001, Q-005 (late callback → `410`).
- PRD-04: Q-001 … Q-009 with Testcontainers PostgreSQL + RabbitMQ.
- PRD-10: CHAOS-001/002 repeat the crash and duplicate drills ≥ 10 times.

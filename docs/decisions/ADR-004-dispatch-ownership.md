# ADR-004: Dispatch and scheduling ownership — the DB dispatcher decides, the broker only transports

**Date:** 2026-11-19 | **Status:** Proposed (accepted once the developer signs off in `docs/evidence/PRD-00.md`)

## Context
- PRD-04 introduces RabbitMQ; PRD-11 introduces duration-aware scheduling (`LPT_AGING`) and must be benchmarked against `FIFO`.
- A global browser-session cap (`global_max_sessions`, invariant I5) must hold across all workers and engines.
- If every queued target is published to the broker immediately, the broker's queue order — not TestGrid's policy — decides what runs next, and the cap can only be approximated with per-consumer prefetch.
- v1 of the kit left this ambiguous (CHANGES_v2 conflict #6); ARCHITECTURE §3 proposes the direction ratified here.

## Options considered
- **A. DB dispatcher owns order and cap; broker is transport (chosen).**
  - A loop inside the API (~250 ms tick, plus nudges on `RUN_CREATED` and on completions) takes `pg_advisory_xact_lock`, computes `free = global_max_sessions − active_attempts − dispatched_unclaimed`, selects up to `free` eligible targets by the active policy, sets `dispatched_at`, increments `dispatch_generation` and inserts `outbox_events` — all in one transaction.
  - Strengths: the broker queue holds at most `cap` messages, so the policy really controls execution order; the cap is enforced in one place; dispatch generations act as fencing tokens against stale redeliveries.
  - Weaknesses: a central component; up to one tick of added latency; the advisory lock serialises dispatch.
- **B. Publish everything at creation; broker FIFO plus consumer-side priority.**
  - Rejected because: consumers only see what is at the head of their own prefetch window, so a "smart" consumer cannot reorder work it hasn't received; per-engine queues make a *global* cap impossible to enforce without a second coordination mechanism; and the PRD-11 benchmark would measure the broker, not the scheduler.
- **C. RabbitMQ priority queues (`x-max-priority`).**
  - Rejected because: priorities are a small fixed integer range and cannot express LPT-with-aging (a continuous score that changes as targets wait); messages already in the queue cannot be re-prioritised; and the global cap problem from B remains.
- **D. Pure DB polling (workers claim directly forever, no dispatcher).**
  - Rejected because: it gives up the broker delivery semantics ADR-002 adopts for PRD-04+, and the "policy" would live in each worker's claim query, racing under concurrency rather than being decided once under a lock.

## Decision
- Run creation inserts targets as `QUEUED` and publishes nothing.
- The dispatcher is the only component that decides *which* targets run *when*; the scheduling policy (`FIFO` in PRD-04, `LPT_AGING` in PRD-11) is a pluggable interface inside it.
- RabbitMQ carries `{messageId, targetId, dispatchGeneration}` and nothing that encodes priority.
- Defence in depth: the claim endpoint re-checks capacity (`429 capacity-exhausted`) and generation (`409 stale-dispatch`).

**What this does NOT claim:**
- Not that the dispatcher is highly available: it is single-instance in local mode; the advisory lock only makes several copies *safe*, not *fast*.
- Not that the extra latency is negligible — it must be measured (dispatch latency = `dispatched_at − queued_at`, ARCHITECTURE §11) before any statement about it.
- Not that LPT_AGING will beat FIFO; PRD-11 reports the cases where it doesn't.

## Consequences
- **Benefits:** one place for ordering and capacity; scheduler benchmarks measure the policy; broker queues stay short.
- **Risks:** dispatcher bugs stall all work (mitigated by the I7 validator and `testgrid_outbox_unpublished`); lock contention under load (measured in PRD-12 k6/NoopExecutor baseline).
- **Invariants:** I5 (cap), I7 (every committed `QUEUED` target eventually dispatched).
- **Rollback:** setting the policy to `FIFO` reproduces simple queue order without removing the dispatcher.
- **Testing requirements:** Q-001 (20 targets, cap 3, sampled every 200 ms), Q-002 (duplicate message → stale generation), SCHED-001/002, SHD-001.

## Verification
- PRD-04: Q-001, Q-002, Q-003, Q-009; dispatch latency recorded in evidence.
- PRD-11: SCHED-001, SCHED-002, SHD-001, SHD-002 with ≥ 5 paired trials per cell.

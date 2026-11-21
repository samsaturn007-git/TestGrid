# Evidence — PRD-04

**PRD:** `prds/PRD-04-durable-queue.md`  
**Branch / commits:** `prd-04/durable-queue` — `187ba6a` (start, N11–N16); T1 `5ff8113`; T2 `11bdf49`, `ec8ea1f`; T3 `5518982`, `e829fff`, `25921ae`; T4 `fe491ac`, `1b2ae3b`; T5 `dd48145`, `1a48afb`; T6 `af2c046` (deadlock fix), `8cefbe1`, `599f2cf`; T7 `7638915`; tracker ticks `2c04427`, `4ee8b95`, `3b074bb`, `ba83ee6`, `2d09de6`, `0204a76`, `cdba9b4`; evidence `f0816f6`, `30b3819`; review fixes `e6cf093`, `b6d5a06`, `54c3d4f`, `a6b5fa4`. PR https://github.com/samsaturn007-git/TestGrid/pull/5  
**Reviewer:** developer (repository owner). On 2026-11-21 the developer asked Claude to close PRD-04 on their behalf: review PR #5, write the explain-back and run `tools/progress.py done` (see "Review of PR #5" and the last section). Merging the PR stays with the developer.  
**Date:** 2026-11-21 (UTC)  
**Outcome:** pass (all seven ACs verified against Testcontainers and the live Compose stack; GitHub Actions run 44915182214 on `f0816f6` green on both jobs; the review fixes after it were verified locally, and their CI run on the PR is watched by Auto-fix).

Reference machine: Apple M4, 16 GiB, macOS 27.2, OrbStack, JDK 21.0.13.1, Node 26.13.1, pnpm 12.21.0, PostgreSQL 18.7, RabbitMQ 4.4.1 (`rabbitmq:4.4.1-management`), Spring Boot / Spring AMQP 4.1.2 (amqp-client 5.30.0), amqplib 2.4.0, `@playwright/test` 1.65.1. API tests are **live** against Testcontainers PostgreSQL and RabbitMQ. The drills are **live** against Compose with real browsers. The T6 crash tests use a **simulated** worker (see "Skipped or mocked tests"). Only the fake values from `.env.example` were used.

## Acceptance criteria with evidence

| Criterion | Pass? | Test ID(s) | Proof (file, test and observable behaviour) |
|---|---|---|---|
| AC-1 | Yes | Q-001 | `CapTest` (5/5): 20 targets, `global_max_sessions` 3, two workers with 4 slots in total so the cap is what limits; active `CLAIMED`/`RUNNING` attempts sampled from the database every 25 ms (denser than the 200 ms asked): **max 3 in every run** (75–85 samples per run), and 3 was reached; 20 attempts, all `PASSED`. Also `DispatcherTest` (cap and FIFO, 8 concurrent ticks release exactly 3) and `ClaimConcurrencyTest.concurrentClaimsNeverGoOverTheCap` (cap lowered after dispatch: one `200`, one `429`, 3/3). Raw: `raw/prd-04-crash-tests.txt`. |
| AC-2 | Yes | Q-002 | `DuplicateMessageTest` (5/5): one dispatch published three times (same body, same `messageId`) and consumed by three slots on two workers at once → one `TARGET_CLAIMED`, one attempt `PASSED`, one `TARGET_FINISHED`; the two copies got `409 stale-dispatch` and were acked; queue empty. `ClaimConcurrencyTest` (two concurrent claims of one message → `200` + `409`, 3/3; ten claims of four messages → four attempts, 3/3). |
| AC-3 | Yes | Q-003, Q-008 | Crash after publish, before mark: `PublishCrashTest` (5/5, `TG_FAULT=after_publish_before_mark`) — the row stays unpublished, is re-published with the same `messageId`, the copy gets `409`; one attempt `PASSED`; `publish_attempts` 1. Also `OutboxPublisherTest`. Crash after complete, before ack: `AckCrashTest` (5/5) — the redelivery gets `409` and is acked, one attempt. **Live with the real worker:** `make crash-check` (`TG_FAULT_WORKER=after_complete_before_ack`): container exits 137 after completing, target `PASSED` with `1:PASSED`, the restarted worker refuses the redelivery (`409`) and acks it, engine queues empty (`raw/prd-04-crash-check.txt`). |
| AC-4 | Yes | Q-004 | `WorkerKillTest` (lease TTL 5 s, max backoff 1 s, bound 3 × (5 + 1) = 18 s): worker dies after its claim → attempt 1 `ABANDONED`/`LEASE_EXPIRED` kept with 0 case results, attempt 2 `PASSED`, recovered **5.6–5.8 s** after the crash (5/5); worker dies on every attempt → `INFRA_ERROR` with `1:ABANDONED, 2:ABANDONED, 3:ABANDONED` **16.0–16.2 s** after the first crash (5/5). **Live** (TTL 30 s): `make kill-check` — attempt 2 claimed **33 s** after `kill -9` (drill bound 45 s), events `… ATTEMPT_ABANDONED > TARGET_RETRY_SCHEDULED > TARGET_DISPATCHED > TARGET_CLAIMED …` (`raw/prd-04-kill-check.txt`); `make crash-check` `after_claim` — `1:ABANDONED:LEASE_EXPIRED, 2:PASSED` 37–39 s after the kill. `ReaperTest` (8 tests) for the reaper's own rules. |
| AC-5 | Yes | Q-006 | `BrokerRestartTest` (5/5): two 3-target runs on a worker with 2 slots; `docker restart` of the RabbitMQ container while attempts run. Each time: the worker's connection was force-closed (`CONNECTION_FORCED … 'shutdown'`), 2 acks were lost and the redeliveries got `409`, 2 outbox rows needed a publish retry, and **all 6 targets ended `PASSED`** about 5.6 s after the restart began; no outbox row left unpublished, no attempt `ABANDONED`, 6 attempts for 6 targets. The `docker restart` call returned after ~220 ms (container restarted); RabbitMQ took longer to accept connections. |
| AC-6 | Yes (metric deferred) | Q-009 | `DeadLetterTest` (5/5): every delivery makes the worker die before claiming → each dispatch is dead-lettered after 1 + 5 deliveries, the reaper's dispatch timeout sends a new generation, and after 3 unclaimed dispatches the target is `INFRA_ERROR` (`WORKER_LOST`); **DLQ depth 3**, 18 crashes, 0 attempts, 2 `TARGET_RETRY_SCHEDULED` (`DISPATCH_TIMEOUT`). `TopologyTest` proves the delivery limit on RabbitMQ 4.4.1 (exactly 6 deliveries). The PRD's "metric increments" (Q-009, `testgrid_dlq_depth`) is **not implemented**: metrics are PRD-12 (ARCHITECTURE §8); the depth is read from the broker and `make dlq`. |
| AC-7 | Yes | Q-007 | `RunCancelTest` over real HTTP, cancel staggered 0–36 ms so both orders occur: cancel vs completion **9 completion-first / 1 cancel-first**, cancel vs claim **9 claim-first / 1 cancel-first** (10 runs each, same tally on two consecutive runs); every run ends with one terminal state, one `TARGET_FINISHED` and one `RUN_CANCEL_REQUESTED` (`accepted: false` when the run was already terminal). `CancelRaceTest` with a worker in the loop: cancel reaches a running attempt via its heartbeat (stopped ~1.0 s later, `CANCELLED`, 5/5); a cancel around the end of the attempt → 2 `CANCELLED` + 3 `PASSED`, always one terminal state (5/5). Live: a 3-target run with 2 running and 1 dispatched-waiting → `202`, the waiting one `CANCELLED` at once, both running ones `CANCELLED` within 10 s. Raw: `raw/prd-04-cancel-races.txt`, `raw/prd-04-crash-tests.txt`. |

## Tasks completed

| Task | Commit(s) | Notes |
|---|---|---|
| Setup | `187ba6a` | RabbitMQ 4.4.1 and Spring AMQP re-verified; CONFLICT_REGISTER N11–N16 recorded. |
| T1 | `5ff8113` | `V4__dispatch.sql`: `outbox_events` (immutable content, published stays published, no `DELETE`), `workers` (one live row per name), `scheduler_settings` (single row, the one source for cap, retries and TTL, N15); `run_targets_dispatchable` index. The `run_targets` dispatch columns already existed (N12). |
| T2 | `11bdf49`, `ec8ea1f` | RabbitMQ in the default Compose profile (N14). Dispatcher under `pg_advisory_xact_lock`, FIFO, `free = cap − active − dispatched_unclaimed`; `TARGET_DISPATCHED` to outbox and `run_events` in one transaction. Outbox publisher with correlated confirms, mandatory flag and returns, backoff. Full topology declared up front (queue arguments can't be changed later). |
| T3 | `5518982`, `e829fff`, `25921ae` | `POST /internal/v1/targets/{id}/claim` (generation fencing, `409`/`429`), `claims/next` removed (N11). Worker AMQP consumer: manual acks, prefetch = slots, retry queue (TTL + `CC`), dead-lettering, reconnect, drain. Found on RabbitMQ 4.4.1: a requeue doesn't count toward the delivery limit, and an ack followed straight by `connection.close()` was lost (fixed: close the channel first). |
| T4 | `fe491ac`, `1b2ae3b` | Reaper: expired leases → `ABANDONED`, retry with 2 s × 2ⁿ⁻¹ ± 20 % or `INFRA_ERROR`; lost dispatches → new generation or `INFRA_ERROR`/`WORKER_LOST` (N17). `kill-check` asserts recovery (N16). |
| T5 | `dd48145`, `1a48afb` | Worker registry (register, heartbeat, drain; `LOST` after 3 × TTL; `GET /api/v1/workers`); `POST /api/v1/runs/{id}/cancel` with race handling; no fixed worker ID (N13). |
| T6 | `af2c046`, `8cefbe1`, `599f2cf` | Testcontainers crash suite, every scenario 5×; it found a **deadlock** between concurrent completions of one run (fixed, regression test). Worker `TG_FAULT` points and `make crash-check`. |
| T7 | `7638915` | `docs/RUNBOOK.md` (failure guarantees, timing defaults, stuck runs, DLQ, workers, cancel, drills); `make stuck-runs`, `make dlq`, `make dlq-replay`. |

## Changed files

- `api/src/main/resources/db/migration/V4__dispatch.sql`: outbox, workers, scheduler settings, dispatch index.
- `api/src/main/java/dev/testgrid/api/dispatch/*`: `Dispatcher`, `DispatchRepository`, `OutboxPublisher`, `OutboxRepository`, `DispatchLoops` (dispatcher, outbox, reaper threads), `DispatchSignal`, `DispatchProperties`, `Topology`, `FaultInjector`, `Reaper`, `ReaperRepository`, `ReaperProperties`, `SchedulerSettings*`.
- `api/src/main/java/dev/testgrid/api/internal/*`: targeted claim, worker registry endpoints, run lock before event writes (`AttemptRepository.lockRun`).
- `api/src/main/java/dev/testgrid/api/run/{RunCancelService,RunCancelRepository,RunController}.java`; `worker/{WorkerRegistry,WorkerRepository,WorkersController,WorkerView,WorkerState}.java`; `problem/ProblemType.java` (`stale-dispatch`, `capacity-exhausted`); `common/Traceparent.java`.
- Tests: `dispatch/*Test` (Dispatcher, OutboxPublisher, DispatchLoop, DispatchProperties, Topology, Reaper, ReaperProperties), `internal/{TargetClaimTest,ClaimConcurrencyTest,RunCancelTest,WorkerRegistryTest,ConcurrentCompletionTest}`, `crash/*` (simulated worker, evidence recorder, 8 classes); PRD-03 tests moved to the targeted claim.
- `worker/src/{consumer,dispatch-message,registration}.ts`, `worker.ts` (delivery decisions, fault points), `api-client.ts`, `config.ts`, `main.ts`; `worker/test/{consumer,dispatch-message,registration,config}.test.ts`, `worker.test.ts`.
- `infra/compose/compose.yaml` (rabbitmq; worker broker settings, slots, no fixed ID, fault variable), `.env.example`, `Makefile`, `scripts/{kill-check.sh,crash-check.sh,stuck-runs.sh,dlq.sh,dlq.mjs,sql/stuck-runs.sql}`.
- `api/openapi.yaml`, `packages/contracts/src/api.ts` (regenerated).
- Docs: contracts §2.2, §4, §5, §6, §7, §9; ARCHITECTURE §2, §4 (lost dispatch, lock order), §6, §9; `docs/RUNBOOK.md`; `docs/VERSIONS.md`; `docs/CONFLICT_REGISTER.md` N11–N17 (all closed); MASTER_PRD profile list.

## Design notes (for review)

- **Database decides, broker delivers (ADR-004).** The dispatcher releases at most `cap − active − dispatched_unclaimed` targets per tick under one advisory lock, so the broker queue never holds more than the cap and FIFO is decided in SQL. The claim takes the same lock and re-checks capacity.
- **Outbox.** The dispatch state change and the message are one transaction; the publisher only marks a row after a publisher confirm with no return. A crash in between re-sends the same message, which is harmless because…
- **…the generation is the fencing token.** Each dispatch increments `dispatch_generation`; the claim requires the message's generation to equal the target's, the target to be `QUEUED` and dispatched, and the run not to have a cancel request. Every other case is `409` and the worker acks.
- **Ack ordering.** The worker acks only after the completion was accepted (or the database has otherwise decided). A crash before the ack means a redelivery, and the redelivery meets a terminal target.
- **Lock order.** Target before run everywhere, and the run's `FOR UPDATE` before the first `run_events` insert (see the T6 finding below).

## Actual executed commands and outputs

### Suites (all on the final code unless noted)

```text
$ make test-api          # mvn verify, Testcontainers PostgreSQL 18.7 + RabbitMQ 4.4.1
51 classes, 328 tests, 0 failures, 0 errors, 0 skipped        exit 0   (raw/prd-04-api-tests.txt; after the review fixes)
$ make test-node
demo-shop test: ℹ tests 42  ℹ fail 0
worker test:    ℹ tests 82  ℹ fail 0                         exit 0
$ make check             # prettier, eslint --max-warnings=0, tsc, spotless:check compile
exit 0
$ make contracts-check
contracts-check OK: api/openapi.yaml and packages/contracts/src/api.ts match the code
$ bash tools/vocab_check.sh
vocab_check OK
$ python3 tools/progress.py lint
lint OK: tracker matches PRD files
```

These were rerun after the PR #5 review fixes (below): 328 API tests (the 10 new ones are the two regression tests), crash suite 10 scenarios at 5/5 again, `make e2e` and `make kill-check` OK on the rebuilt stack.

### Crash suite pass counts (`raw/prd-04-crash-tests.txt`, generated by `EvidenceRecorder`)

```text
AckCrashTest.aCrashAfterCompleteBeforeAckLeavesOneAttempt: 5/5 passed
BrokerRestartTest.aBrokerRestartMidRunLosesNoTarget: 5/5 passed
CancelRaceTest.aCancelAtTheEndOfTheAttemptResolvesToOneTerminalState: 5/5 passed
CancelRaceTest.aRunningAttemptIsStoppedThroughItsHeartbeat: 5/5 passed
CapTest.twentyTargetsWithCapThreeNeverRunMoreThanThree: 5/5 passed
DeadLetterTest.exhaustedDispatchesEndInfraErrorWithTheMessagesInTheDeadLetterQueue: 5/5 passed
DuplicateMessageTest.theSameMessageThreeTimesGivesOneLeaseAndOneResult: 5/5 passed
PublishCrashTest.aCrashBetweenPublishAndMarkIsHarmless: 5/5 passed
WorkerKillTest.aKilledWorkersTargetIsRetriedAndTheAbandonedAttemptIsKept: 5/5 passed
WorkerKillTest.aTargetWhoseWorkerAlwaysDiesEndsInfraErrorWithinTheBound: 5/5 passed
```

These counts are from the crash classes inside the full `make test-api` run. The suite also passed on its own before that, 50/50.

### GitHub Actions (`raw/prd-04-ci.txt`)

Run 44915182214 on `f0816f6` (PR #5) passed on fresh `ubuntu-24.04` runners: https://github.com/samsaturn007-git/TestGrid/actions/runs/44915182214

```text
check + unit tests: success (5m19s)
  make check (format, lint, typecheck)                         success
  make test (node:test + ./mvnw verify)                        success   demo-shop 42/42, worker 82/82; mvnw -q prints no per-class counts
  Vocabulary check                                             success   vocab_check OK
  Contract drift check                                         success   contracts-check OK
compose up + Chromium smoke and worker e2e: success (4m16s)
  make up (all services healthy, RabbitMQ included)            success
  Run survives an API restart (PRD-02 AC-1)                    success
  Worker end-to-end on Chromium (PRD-03 E2E-001, AC-1, AC-2)   success   (AMQP consumer and targeted claim)
  make smoke, fixture isolation, fixtures disabled → 404       success
```

The crash suite ran on the runner. The log shows each fault point firing as many times as the local tallies predict, the broker restart's `CONNECTION_FORCED`, and the reaper's abandon and lost-dispatch passes (counts in the raw file). The fixed-port RabbitMQ container works on hosted runners.

### Live drills (Compose, real worker image and browsers)

```text
$ make e2e                 e2e OK (5 scenarios, Chromium)                                raw/prd-04-e2e.txt
$ make kill-check          attempt 2 claimed 33 s after the kill … kill-check OK        raw/prd-04-kill-check.txt
$ make crash-check         after_complete_before_ack: 1:PASSED, redelivery refused (409)
                           after_claim: 1:ABANDONED:LEASE_EXPIRED, 2:PASSED … crash-check OK   raw/prd-04-crash-check.txt
$ make sigterm-check       sigterm-check OK                                             raw/prd-04-sigterm-check.txt
$ make restart-check       restart-check OK
$ make stuck-runs / make dlq / make dlq-replay                                          raw/prd-04-runbook-tools.txt
```

Other live checks during the PRD:
- **Scaling:** `--scale worker=2` registered two `worker-<hostname>` names, and e2e attempts were spread 2 / 3 across them.
- **Leftover messages:** the 5 messages left over from T2 were refused (`409`) and acked once the consumer arrived.
- **Old stuck runs:** two attempts stuck since the PRD-03 drills were recovered by the reaper on its first start.
- **Errors:** the API logged 0 `ERROR` lines across the drills.

### Dispatch latency (`raw/prd-04-dispatch-latency.txt`)

21 live targets that didn't wait for a slot: create → dispatch p50 **7 ms**, max 21 ms; dispatch → publish confirmed p50 **4 ms**, max 8 ms. This is one machine and one session, not a benchmark. Targets that waited for capacity are excluded.

### Claim query plan (`raw/prd-04-explain-target-claim.txt`)

With 5,000 targets, ANALYZEd: `Index Scan using run_targets_pkey on run_targets` under `LockRows`, no sequential scan on `run_targets` (`ClaimExplainTest`).

## Review of PR #5 (2026-11-21, at the developer's request)

A code review of the whole branch (`main...prd-04/durable-queue`) found seven issues. Six are fixed with a test or a live check; one is left.

| Finding | Severity | Fix | Proof |
|---|---|---|---|
| `make dlq-replay` could replay a message forever if it was dead-lettered again while the tool ran | important | `54c3d4f`: list and replay read at most the messages present at the start | Live: a message the API refuses with `400` was replayed once, dead-lettered again, and the tool finished |
| Two reapers (two API instances) could deadlock taking run locks in opposite orders | important | `e6cf093`: a pass locks every run it touches in id order before any event | `ReaperTest.twoReapersWithOverlappingRunsDoNotDeadlock`: `deadlock detected` 5/5 before, 5/5 pass after |
| The lost-dispatch limit counts unclaimed dispatches over the target's life, but the docs said "in a row" | important | `a6b5fa4`: the docs now say "in total" (the code was kept) | contracts §2.2/§9, ARCHITECTURE §4, RUNBOOK §1, N17 |
| Two registrations of one worker name at once could answer `500` (unique-index race) | nit | `b6d5a06`: advisory lock on the name | `WorkerRegistryTest.concurrentRegistrationsOfOneNameLeaveOneLiveRow`: 5/5 failed before, 5/5 pass after |
| A message still waiting for capacity past the dispatch timeout counts toward `WORKER_LOST` | nit | `a6b5fa4`: documented in `ReaperProperties` and RUNBOOK §2 | — |
| `stuck-runs` hard-coded the 5-minute dispatch timeout; Compose forwarded none of the reaper settings to the API | nit | `54c3d4f`: `stuck-runs` reads `TESTGRID_DISPATCH_TIMEOUT_MS`, and Compose forwards all four reaper variables | Live: `env` in the API container shows them; `make stuck-runs` runs with the variable |
| The crash suite's free-port choice for RabbitMQ is a small time-of-check race | nit | Fixed after it bit: CI run 44918053915 on `7004414` failed all 50 crash tests with `address already in use` on a random binding for port 15671. `CrashRabbit` now exposes only AMQP, picks the port inside the create step and tries the start 3 times | Locally: crash suite 50/50, `make test-api` 328/328 |

## Negative / failure tests

- **Deadlock between concurrent completions (found by T6, fixed in `af2c046`).**
  - Injected by: three targets of one run completing at the same instant (`ConcurrentCompletionTest`). It was first seen in `CapTest` as 22 attempts for 20 targets.
  - Observed before the fix: 10/10 runs failed with `deadlock detected` on `SELECT status FROM runs … FOR UPDATE` (`raw/prd-04-deadlock-before-fix.txt`). Each completion's `run_events` insert takes `FOR KEY SHARE` on the run through the foreign key, and the later `FOR UPDATE` was a lock upgrade. The aborted completion's lease then expired and the reaper retried the target, so the system recovered, but with a wasted attempt.
  - After the fix: completion and the reaper lock the run before their first event; 10/10 pass. The real worker retries a `5xx` completion, so in production this would mostly have shown up as retries and occasional extra attempts.
- **Lost ack on close (T3).** `sigterm-check` passed, but the message came back after the restart. A probe in the worker container showed that an ack followed straight by `connection.close()` left the message in the queue (1 left), while closing the channel first left none (0). The worker now closes the channel first; the drill rerun showed no redelivery.
- **Delivery limit (T3).** My first delivery-limit test looped forever: on RabbitMQ 4.4.1, `basic.nack` with requeue doesn't count toward `x-delivery-limit`. Only a consumer dying with the message unacked counts. `TopologyTest` now asserts both behaviours.
- **Stale and malformed claims:** `TargetClaimTest`: older or newer generation, requeued, terminal, cancel requested → `409`; at the cap → `429`; unknown → `404`; malformed → `400`; nothing is written in any of those cases.
- **Late callbacks after a reaper retry:** `ReaperTest`: the old worker's redelivery gets `409`, and its heartbeat and completion get `410`.
- **Worker registry:** `WorkerRegistryTest`: re-registering marks the old row `LOST`, which then answers `410`; silence for 91 s → `LOST` while 89 s → still `ACTIVE`; bad names, versions and slots → `400`.
- **Worker decisions:** `worker/test/{worker,consumer,registration,config}.test.ts`: `409`/`404` → ack, `429`/`5xx`/`401`/network → retry queue, unparseable/`400` → dead letter; retry TTL and `CC`; unconfirmed retry publish → requeue, no ack; slot limit; shutdown requeues unstarted messages; reconnect; fault points fire only at their point; `TG_FAULT` refused outside `local`.

## Security and threat check

- **Input restrictions:**
  - Claim and registry bodies are strictly parsed: unknown fields and wrong types → `400`. `workerId`/`name` and `version` have patterns, slots are 1–16, and the generation is ≥ 1.
  - The worker validates every dispatch message with zod (`strictObject`, uuid IDs, `schemaVersion: 1`). It dead-letters anything else and never builds a Playwright argument from the message: the target and its cases come from the claim and the manifest (ADR-003).
  - `TG_FAULT` is refused outside `TESTGRID_PROFILE=local` on both the API and the worker.
- **Secrets:**
  - The broker password comes from `.env` only (`.env.example` holds a fake value), is passed to amqplib as a field rather than a URL, and is in the worker logger's redaction list.
  - Live log scan of 840 lines (API, worker, RabbitMQ): 0 service tokens, 0 broker passwords, 0 `Bearer` values, 0 lease tokens (`raw/prd-04-secret-scan.txt`).
- **New attack surface and mitigation:**
  - **RabbitMQ:** internal network only, no published ports, its own volume, 512 MiB, `no-new-privileges`.
  - **Admin endpoints:** the DLQ tool and `stuck-runs` add none. They run as one-off containers or `psql` through `docker compose`, and need the operator's Docker access.
  - **Internal endpoints:** the registry and claim endpoints sit on the internal port behind the service token, like the PRD-03 worker API.
  - **`GET /api/v1/workers`** is public on the local profile, like the other read endpoints; it exposes worker names (container hostnames), engines and slots. Access control is PRD-12.
  - **`POST /runs/{id}/cancel`** is unauthenticated on the local profile, like run creation, and every request is recorded as an event. Authentication and rate limits are PRD-12.

## Skipped or mocked tests

- **The T6 crash tests use a simulated Java worker (`crash/SimWorker.java`), not the Node worker.**
  - What it does: a real AMQP consumer (manual acks, prefetch = slots) calling the real `AttemptService`, crashing by aborting its channel with the delivery unacked. There's no Playwright: an attempt sleeps and reports every case `PASSED`.
  - What covers the Node worker instead: its decision table is unit-tested against an in-memory channel and a fake HTTP API (**mocked**), and its fault points are run **live** by `make crash-check` (real image, broker and browsers).
- **The crash tests shorten the timings:** lease TTL 5 s, heartbeat 1 s, reaper 200 ms, retry backoff 200 ms (max 1 s), dispatch timeout 2 s. The bounds are computed from those values; the live drills use the defaults.
- **AC-6's metric** is not implemented (PRD-12).
- **CI coverage:** GitHub Actions (run 44915182214) runs `make test`, which now includes the crash suite and took 4m20s, plus `make up`, `restart-check` and the Chromium e2e. The drills (`kill-check`, `crash-check`, `sigterm-check`), `stuck-runs` and the DLQ tools ran locally only.
- **Not run in this PRD:** the three-engine e2e (`ENGINES="chromium firefox webkit"`), and the demo with 3 workers, 20 targets and one `docker kill`. Two workers and a 3-engine cancel were run live.

## Screenshots / demo / raw data links

- `docs/evidence/raw/prd-04-ci.txt` (GitHub Actions run 44915182214).
- `docs/evidence/raw/prd-04-api-tests.txt`, `prd-04-crash-tests.txt`, `prd-04-cancel-races.txt`, `prd-04-deadlock-before-fix.txt`, `prd-04-explain-target-claim.txt`.
- `docs/evidence/raw/prd-04-e2e.txt`, `prd-04-kill-check.txt`, `prd-04-crash-check.txt`, `prd-04-sigterm-check.txt`, `prd-04-runbook-tools.txt`, `prd-04-dispatch-latency.txt`, `prd-04-secret-scan.txt`.

## Known limitations and deferred improvements

- **No DLQ metric or alerts** (`testgrid_dlq_depth`, outbox lag, active sessions above the cap): PRD-12.
- **The dispatcher doesn't use the registry's slot counts.** With more cap than worker slots, a message can wait in a queue. Past the 5-minute dispatch timeout it is dispatched again: harmless, but each re-dispatch counts toward the `WORKER_LOST` limit. Slot-aware scheduling is PRD-11.
- **Registry liveness is coarse.** A killed worker stays `ACTIVE` for up to 90 s, so `stuck-runs` can miss `NO_ACTIVE_WORKER_FOR_ENGINE` for that long (seen live). A drained worker shows `DRAINING` until it turns `LOST`, and `LOST` rows accumulate (the list shows only the last hour).
- **Dead-lettering is at-most-once** (quorum queue default). The dispatch timeout covers a message lost in transit; at-least-once dead-lettering would need different queue arguments, which RabbitMQ refuses on existing queues.
- **Execution is not exactly-once.** A worker that is alive but cut off from the API past its TTL loses the attempt while its browser may still be running. The results stay single (`410`), but the work is duplicated.
- **Trace context:** each dispatch starts a new trace (not linked to the HTTP request that created the run); the worker continues it. Full OpenTelemetry export is PRD-12.
- **The crash suite adds about 4 minutes** to `make test-api`.

## Explain-back review

> **Written by Claude at the developer's request, not in the developer's own words.** On 2026-11-21 the developer asked Claude to close PRD-04 on their behalf, as they did for PRD-03. PRD-04's definition of done says "the developer can explain the outbox, generations and ack ordering without AI". These answers don't show that, so that item is **still owed**: the developer should answer the quiz below without notes and replace these answers. The PRD-01–03 explain-backs are also still owed (MASTER_PRD §9).

### Quiz (answer without notes)

1. The dispatcher has committed `TARGET_DISPATCHED` for a target, and the API process dies before the outbox publisher runs. What is in the database, what is in the broker, and what happens when the API starts again?
2. The publisher got the broker's confirm, then the API died before it marked the row published. Why is the second publish harmless? Name the exact check that makes it so.
3. Why does the worker ack *after* `complete` and not after the claim? What would go wrong if it acked after the claim and then crashed?
4. A worker is killed after its claim. Walk through every state change until the target runs again: attempt status, target status, `dispatched_at`, `dispatch_generation`, `not_before`, and what the redelivered old message gets.
5. What is the formula for `free` in the dispatcher, and why does it subtract `dispatched_unclaimed`? What breaks if it doesn't?
6. Why do the claim and the dispatcher take the same advisory lock? Describe the race that `ClaimConcurrencyTest.concurrentClaimsNeverGoOverTheCap` would catch without it.
7. A message ends in `tg.targets.dead`. Is the target lost? Who recovers it, after how long, and when does it become `INFRA_ERROR` instead?
8. On RabbitMQ 4.4.1, which of these count toward `x-delivery-limit`: a `basic.nack` with requeue, or a channel closed with the message unacked? Why does the worker care?
9. A cancel and the last completion of a run arrive together. List the two possible outcomes and what `POST /cancel` returns in each.
10. Explain the deadlock T6 found: which locks, in which order, and why locking the run before the first event insert fixes it.

### Claude's answers (on the developer's behalf)

1. **Committed but not published.**
   - **Database:** the target is `QUEUED` with `dispatched_at` set and `dispatch_generation` incremented. The `outbox_events` row exists with `published_at` null, and `TARGET_DISPATCHED` is in `run_events`.
   - **Broker:** nothing.
   - **On restart:** the publisher's first poll finds the row (`published_at IS NULL`, `next_attempt_at <= now()`), publishes it with a confirm, and marks it. Nothing is lost, because the message is promised in the same transaction as the state change (invariant I7). Meanwhile the target counts as `dispatched_unclaimed`, so its cap slot stays reserved.
2. **The duplicate publish.** Both copies carry the same body: same `targetId`, same `dispatchGeneration`. The claim locks the target and requires `status = QUEUED`, `dispatched_at IS NOT NULL`, the message's generation equal to the target's, and no cancel request. The first copy claims and the target becomes `CLAIMED`; the second copy gets `409 stale-dispatch` ("the target is already claimed", or "already PASSED" if it arrives later), and the worker acks it. The partial unique index `attempts(target_id) WHERE status IN ('CLAIMED','RUNNING')` would refuse a second active attempt even if that check were wrong.
3. **Ack after `complete`.** Until `complete` commits, the message is the only thing that can bring the target back if the worker dies before it holds a lease: a crash between delivery and claim, or a claim whose response was lost.
   - **What acking after the claim would cost:** if the worker then crashed, nothing would be lost, because the lease would expire and the reaper would requeue it. But it would give up the redelivery safety net for no gain.
   - **What acking after `complete` costs:** a crash between `complete` and the ack makes the broker redeliver, and that redelivery meets a terminal target and gets `409`. So the order costs one harmless duplicate and loses nothing.
   - **Why not ack before the claim:** a crash right after the ack would leave a dispatched target with no message, waiting for the dispatch timeout.
4. **Killed after the claim.**
   - The attempt is `CLAIMED` (or `RUNNING`) and the target `CLAIMED`/`RUNNING`, with `attempt_count` 1. Heartbeats stop.
   - The broker sees the channel close and redelivers the message, generation 1. The restarted worker claims it and gets `409` ("already claimed"), and acks it.
   - After `leased_until < now()`, the reaper (every 5 s) marks attempt 1 `ABANDONED` / `LEASE_EXPIRED` and keeps it. The target goes back to `QUEUED` with `dispatched_at = NULL` and `not_before = now() + 2 s ± 20 %`, and events `ATTEMPT_ABANDONED` and `TARGET_RETRY_SCHEDULED` are written.
   - When `not_before` passes, the dispatcher sets `dispatched_at` and `dispatch_generation = 2` and writes a new outbox row. A worker claims generation 2: attempt 2 is `CLAIMED`, `attempt_count` 2.
   - Any later copy of generation 1 gets `409` ("not the current generation"). A late heartbeat or completion from the old worker gets `410 lease-expired`.
5. **The cap formula.** `free = global_max_sessions − active − dispatched_unclaimed`, where `active` is the `CLAIMED`/`RUNNING` attempts and `dispatched_unclaimed` is the `QUEUED` targets with `dispatched_at` set. A dispatched target will become active as soon as a worker claims it, so its slot is spoken for. Without that term, each tick would see the same free slots and dispatch again, and the queue would fill with more messages than the cap. Workers would then claim them as fast as they can, and active sessions would exceed the cap (invariant I5). The claim's re-check would turn many of those into `429`s and retry-queue traffic.
6. **The shared advisory lock.** Both read "how many are active" and then act on it. Without one lock, two claims at cap 1 could each count 0 active attempts in their own snapshot, both pass the `active < cap` check, and both commit: 2 sessions with a cap of 1. The test sets up exactly that (two dispatched targets, cap lowered to 1, two concurrent claims) and expects one `200` and one `429`. The same lock also stops a dispatch tick and a claim from interleaving their counts.
7. **A dead letter.** The target isn't lost: it stays `QUEUED` and dispatched in the database. The reaper's lost-dispatch scan finds it once `dispatched_at` is older than the dispatch timeout (5 min by default) and clears `dispatched_at`; the dispatcher then sends a new generation. It ends `INFRA_ERROR` (`WORKER_LOST`) once `dispatch_generation − attempt_count` reaches `max_attempts`, which counts its unclaimed dispatches in total. An operator can skip the wait with `make dlq-replay` while the message is still the current generation.
8. **What counts toward the delivery limit.** Only the channel closed with the message unacked; the nack with requeue doesn't count (checked in `TopologyTest`). So the worker never uses requeue to bound anything. Unparseable messages and `400`s are rejected without requeue (dead-lettered at once), and the delivery limit only catches workers that die. A requeue is used only on shutdown, for messages that never started, so a graceful stop doesn't push messages toward the dead-letter queue.
9. **Cancel vs the last completion.** Whichever transaction commits first wins, because both lock the target before the run.
   - **Completion first:** the target and run are terminal with their real result. The cancel finds a terminal run and answers `200` unchanged, and records `RUN_CANCEL_REQUESTED {accepted: false}`.
   - **Cancel first:** `cancel_requested_at` is set and the cancel answers `202`. The running attempt is untouched, so the completion that follows (already carrying results) still decides the target, normally `PASSED`. A worker that saw `cancelRequested` on a heartbeat before finishing completes `CANCELLED` instead.
   - **Either way:** one terminal state, one `TARGET_FINISHED`, and both requests in `run_events`.
10. **The deadlock.**
    - **The locks:** `run_events.run_id` is a foreign key to `runs`, so every event insert takes `FOR KEY SHARE` on the run row. `complete()` inserted `CASE_FINISHED` events (weak lock on the run), then called `recomputeRun`, which asks for `FOR UPDATE` on the same row.
    - **The cycle:** two completions of different targets of one run each held the weak lock and waited for the other to release it before they could take the strong one. Postgres detected the cycle and aborted one.
    - **The fix:** taking `FOR UPDATE` on the run before the first event insert. The second completion then waits at that point, holding nothing on the run, and proceeds after the first commits; there's no lock upgrade left. Claim and cancel already did it in that order. The reaper does it too, now for all its runs at once, in id order.

### Claude's notes on the three template questions

1. **The three most important decisions:**
   - the transactional outbox with generation fencing instead of exactly-once delivery;
   - the database owning order and the cap, with the broker only delivering (ADR-004);
   - leases and a reaper that keeps every attempt and bounds retries and lost dispatches.
2. **If it fails halfway:** see quiz answers 1–4 and 9. Every crash point ends either in a harmless duplicate refused by the claim, or in a lease or dispatch timeout the reaper recovers within a bounded time.
3. **For production:**
   - metrics and alerts (DLQ depth, outbox lag, active sessions; PRD-12);
   - slot-aware dispatch (PRD-11);
   - a three-node RabbitMQ cluster, with at-least-once dead-lettering decided before the queues are first created;
   - per-worker credentials, and authentication on cancel and `GET /workers` (PRD-12);
   - the crash suite on every change to `dispatch/` and `internal/`;
   - two API instances in a test, to exercise the advisory lock and the reaper's lock order across processes.

# Evidence — PRD-03

**PRD:** `prds/PRD-03-single-worker-runner.md`  
**Branch / commits:** `prd-03/runner` — `b321d7c` (start), `d0ef1e0` (T1), `d79ca8e` (T2), `d89dec1` (T3), `6afd6ed` + `2d1b8c6` (runtime, image, drills), `b35fc0b` (T4–T6 live), `ef5e0de` (T7), `e36a83f` (SKIP LOCKED and EXPLAIN proof); tracker ticks `8c578f7`, `173e08d`, `c91909a`, `d5f8ef1`. Not pushed yet, so no PR or GitHub Actions run exists for this branch.  
**Reviewer:** developer (repository owner); review pending  
**Date:** 2026-11-20 (UTC)  
**Outcome:** pass (all six ACs verified locally; awaiting developer review and the first CI run of the branch)

Reference machine: Apple M4, 16 GiB, macOS 27.2.1, OrbStack / Docker 29.8.2, Compose 5.5.0, JDK 21.0.13.1, Node 26.13.1, pnpm 12.21.0, `@playwright/test` 1.65.1 (Chromium 159.0.8154.6, Firefox 159.0, WebKit 27.2), worker image `mcr.microsoft.com/playwright:v1.65.1-noble` (arm64, ships Node v26.13.1). API tests are **live** against PostgreSQL 18.7 in Testcontainers over real HTTP on both ports. The E2E and drills are **live** against Compose with real browsers. No paid service or real credential was used; the only token is the fake `.env.example` value.

## Acceptance criteria with evidence

| Criterion | Pass? | Test ID(s) | Proof (file, test and observable behaviour) |
|---|---|---|---|
| AC-1 | Yes | E2E-001 | `make e2e` (`scripts/e2e.mjs`): `POST /api/v1/projects/{id}/runs` → the Compose worker claims → real Chromium → run `PASSED`, target attempt 1 `PASSED`, **6 case results** (`docs/evidence/raw/prd-03-e2e-chromium.txt`). Also `catalog-regression` → `PASSED` with 12. Covered at API level by `LeaseProtocolTest.startHeartbeatAndAPassingCompletion`. |
| AC-2 | Yes | E2E-001, UI-002 (data side) | Same live run: `PRODUCT_DEFECT` → run `FAILED`, `cart-total=FAILED/ASSERTION` (only that case); `SELECTOR_DRIFT` → `checkout-happy-path` and `card-declined` `FAILED/SELECTOR_NOT_FOUND`; `TIMEOUT` → run and target `TIMED_OUT`, cases `TIMED_OUT/TEST_TIMEOUT`. No false pass in any mode. Parser tests on recorded 1.65.1 reports: `worker/test/report.test.ts`. The worker's own verdict is never used when results exist: `LeaseProtocolTest.theWorkersOwnVerdictNeverOverridesResults`. |
| AC-3 | Yes | Q-005 (single worker) | `LeaseProtocolTest`: `ac3StaleLeaseIs410AndNothingChanges` (lease pushed into the past → heartbeat and complete `410 lease-expired`, 0 case results, attempt still `RUNNING`); `ac3DuplicateCompletionWithTheSameKeyReplaysAndChangesNothing` (same key → `200`, `Idempotent-Replay: true`, identical body even with a different payload, same 6 case results and event count; different key → `409 idempotency-conflict`); `q005AWrongLeaseTokenIs410`; `q005ALateCompletionAfterTheLeaseWasLostIs410` (attempt `ABANDONED` → `410`). Integration level (real HTTP + Postgres), not against Compose. |
| AC-4 | Yes | Q-004 (detection only) | `make kill-check` (`docs/evidence/raw/prd-03-kill-check.txt`): TIMEOUT-fixture run, target `RUNNING`, `docker compose kill -s SIGKILL worker`, wait 35 s. API: run `RUNNING`, target `RUNNING`, `finishedAt: None`, `latestAttempt.leaseExpired: True`. Postgres: `attempt RUNNING | target RUNNING | run RUNNING | lease_expired t | expired_for_s 10 | case_results 0`. Not marked passed; recovery is PRD-04. API test: `RunResultsApiTest.ac4AnExpiredLeaseIsVisibleAndTheTargetIsNotTerminal`. |
| AC-5 | Yes | — | `LeaseProtocolTest.ac5MalformedCompletionsAre422AndPersistNothing`: unknown case key, negative duration, duration beyond timeout + 60 s, duplicate key, missing case, `FAILED` without an error class, retries beyond the manifest, `INFRA_ERROR` without `workerError`, `CANCELLED` without a cancel request → each `422 invalid-completion`. Afterwards: 0 case results, attempt still `RUNNING`, no `TARGET_FINISHED` event, and the same attempt still completes normally. Shape errors (bad lease token, unknown field, shell characters in `workerId`) → `400` (`shapeErrorsAre400`). |
| AC-6 | Yes | E2E-002 | `make e2e ENGINES="chromium firefox webkit"` (`docs/evidence/raw/prd-03-e2e-three-engines.txt`): all five scenarios give the same correct outcome on all three engines. Platform limitation found and fixed: Firefox needs a writable `HOME` under the read-only root filesystem. Before the fix every Firefox target was reported honestly as `INFRA_ERROR / BROWSER_LAUNCH` with 0 cases (`docs/evidence/raw/prd-03-firefox-home-before-fix.txt`). Engine notes in `docs/SETUP.md`. |

## Tasks completed

| Task | Commit(s) | Notes |
|---|---|---|
| Setup | `b321d7c` | PRD-03 started; pino 10.4.0 and ajv 8.20.0 pinned from the npm registry (`docs/VERSIONS.md` §1.3; Playwright and zod pins re-checked); CONFLICT_REGISTER N8–N10. |
| T1 | `d0ef1e0` | `worker/src/manifest.ts`: ajv against the shared JSON Schema, the same semantic checks as the API, the contracts §10 revision. Both implementations pin the golden revision `3f3ac231f888`; live, the worker loaded `checkout-smoke@29d6afdd879b`, the same revision the API seeded. `catalog-regression` (12 cases, PASS and PRODUCT_DEFECT). `TG_*` env names (N8). |
| T2 | `d79ca8e` | `V3__attempts.sql`; internal port (second Tomcat connector) with constant-time bearer check; claim with `FOR UPDATE SKIP LOCKED`; start, heartbeat and complete as conditional updates against `now()`; one completion transaction; contracts §6/§7. |
| T3 | `d89dec1` | Manifest-only argv builder, `spawn(..., {shell: false, detached: true})`, allowlisted child env, zod report schema and tag mapping, FR-7 classifier, attempt executor. |
| T4 | `6afd6ed`, `2d1b8c6`, `b35fc0b` | Worker loop, API client, results endpoint (FR-9), worker image and Compose service, `make e2e`; CI runs `make e2e` after `make up`. |
| T5 | `d89dec1`, `6afd6ed`, `b35fc0b` | Timeout, cancel and shutdown → process-group `SIGTERM` → `SIGKILL` after 10 s, temporary folder removed; worker `SIGTERM` → 20 s grace → `INFRA_ERROR / WORKER_SHUTDOWN` (`make sigterm-check`). |
| T6 | `6afd6ed`, `2d1b8c6` | pino JSON logs (worker) and Logstash JSON (API) with `runId`, `targetId`, `attemptId`, `workerId`, `traceId`; redaction tests on both sides. |
| T7 | `ef5e0de` | Three-engine E2E; Firefox `HOME` fix; platform notes. |

## Changed files

- `api/src/main/resources/db/migration/V3__attempts.sql`: `attempts`, `case_results`, partial unique index (I1), triggers freezing terminal attempts and case results, `run_events.attempt_id` FK.
- `api/src/main/java/dev/testgrid/api/internal/*`: internal port and access filter, properties, worker API types, controller, `AttemptService` (lease protocol), `AttemptRepository` (SQL).
- `api/src/main/java/dev/testgrid/api/domain/{AttemptStatus,CaseStatus,ErrorClass,TargetOutcome,RunStatus}.java`: contracts §2.1 enums and the §2.2 target-outcome rule.
- `api/src/main/java/dev/testgrid/api/common/{LogContext,Redactor}.java`: MDC scoping and error-text redaction.
- `api/src/main/java/dev/testgrid/api/run/{AttemptSummary,RunResults,RunRepository,RunQueryService,RunController,RunResponse}.java`: FR-9 results, `latestAttempt` with `leaseExpired`.
- `api/src/main/java/dev/testgrid/api/problem/ProblemType.java`, `application.properties` (internal port, JSON logs).
- `api/src/test/java/dev/testgrid/api/internal/*` (7 classes), `domain/TargetOutcomeTest`, `common/RedactorTest`; manifest tests updated for two suites.
- `worker/src/*`: manifest, canonical JSON, claim schema, argv builder, spawn, report, classifier, redactor, attempt executor, config, logger, API client, loop, `main.ts`.
- `worker/test/*` (8 files) and `worker/test/fixtures/` (recorded reports, fake CLIs).
- `worker/suites/catalog-regression.spec.ts`, `worker/manifest/suites.json`, `worker/playwright.config.ts` (three engine projects), `worker/suites/support/*` (`TG_*`).
- `worker/Dockerfile`, `infra/compose/compose.yaml` (worker service, API worker port, `TESTGRID_WORKER_TOKEN`), `.env.example`, `Makefile` (`e2e`, `kill-check`, `sigterm-check`), `scripts/{e2e.mjs,kill-check.sh,sigterm-check.sh}`, `scripts/mode-matrix.sh`, `.github/workflows/ci.yml`, `.prettierignore`.
- `api/openapi.yaml`, `packages/contracts/src/api.ts` (regenerated).
- Docs: `docs/API_AND_DATA_CONTRACTS.md` (§4 results and `latestAttempt`, §6 `invalid-completion`, §7 access, check order and completion rules), `docs/VERSIONS.md` §1.3, `docs/CONFLICT_REGISTER.md` N8–N10, `docs/SETUP.md` (worker and engine notes).

## Design notes (for review)

- **Claiming.** One statement picks the oldest eligible `QUEUED` target and locks it with `FOR UPDATE OF t SKIP LOCKED`; the attempt insert and the target, run and event updates follow in the same transaction. The partial unique index `attempts(target_id) WHERE status IN ('CLAIMED','RUNNING')` makes "one active attempt per target" a database fact, not an application promise.
- **What SKIP LOCKED adds.** Correctness under concurrency holds even with plain `FOR UPDATE`: after the lock wait Postgres re-checks the row, sees it is no longer `QUEUED`, and moves on. The six repeated concurrency tests pass either way. `SKIP LOCKED` removes the waiting, and `aClaimSkipsATargetLockedByAnotherTransactionInsteadOfWaiting` fails with a `TimeoutException` without it (`docs/evidence/raw/prd-03-skip-locked-mutation.txt`).
- **Lease check order.** Unknown attempt `404` → token hash mismatch `410` → terminal (same key `200` replay, `ABANDONED` `410`, other key `409`) → `leased_until <= now()` `410` → `422`. The terminal check comes before the expiry check, so a worker whose `200` was lost can still replay after its lease ran out.
- **Locks.** Claim and complete both lock target before run (`recomputeRun` locks the run row, then reads every target), so two targets of one run finishing together are serialised and the run status is never computed from a stale snapshot.
- **The worker can't vote.** With results, the target status comes from the case results (contracts §2.2); the worker's status only counts when there are none. All-SKIPPED → `INFRA_ERROR / CONFIG`.
- **Nothing from the claim becomes an argument.** Engine and case keys are looked up in the worker's own manifest; tags come from the manifest and are anchored in `--grep`; the worker refuses a claim whose suite revision differs from its own.

## Actual executed commands and outputs

### Full suite (`make check`, `make test`, contracts, vocab, tracker)

```text
$ make check; echo "make check exit=$?"
make check exit=0
$ make test; echo "make test exit=$?"
make test exit=0
demo-shop test: ℹ tests 42  ℹ pass 42  ℹ fail 0
worker test:    ℹ tests 55  ℹ pass 55  ℹ fail 0
$ make contracts-check
contracts-check OK: api/openapi.yaml and packages/contracts/src/api.ts match the code
$ bash tools/vocab_check.sh
vocab_check OK
$ python3 tools/progress.py lint
lint OK: tracker matches PRD files
$ make restart-check
restart-check OK
```

API surefire totals from that `make test` (`docs/evidence/raw/prd-03-api-tests.txt`): **29 classes, 170 tests, 0 failures, 0 errors, 0 skipped**. New PRD-03 classes: `ClaimConcurrencyTest` 7, `ClaimExplainTest` 1, `InternalPortTest` 5, `LeaseProtocolTest` 16, `LogCorrelationTest` 1, `RunResultsApiTest` 4, `TargetOutcomeTest` 2, `RedactorTest` 3.

### AC-1 / AC-2 live (`make e2e`, Chromium)

```text
e2e: engines chromium against http://127.0.0.1:8080
  local:chromium:0 attempt 1 PASSED cases=6 (all PASSED)
ok   checkout-smoke PASS → PASSED in 6.2s
  local:chromium:0 attempt 1 FAILED cases=6 cart-total=FAILED/ASSERTION
ok   checkout-smoke PRODUCT_DEFECT → FAILED in 13.2s
  local:chromium:0 attempt 1 FAILED cases=6 card-declined=FAILED/SELECTOR_NOT_FOUND checkout-happy-path=FAILED/SELECTOR_NOT_FOUND
ok   checkout-smoke SELECTOR_DRIFT → FAILED in 14.2s
  local:chromium:0 attempt 1 TIMED_OUT cases=6 card-declined=TIMED_OUT/TEST_TIMEOUT checkout-happy-path=TIMED_OUT/TEST_TIMEOUT
ok   checkout-smoke TIMEOUT → TIMED_OUT in 43.5s
  local:chromium:0 attempt 1 PASSED cases=12 (all PASSED)
ok   catalog-regression PASS → PASSED in 3.1s
e2e OK
make e2e exit=0
```

### AC-6 live (three engines)

```text
e2e: engines chromium, firefox, webkit against http://127.0.0.1:8080
ok   checkout-smoke PASS → PASSED in 20.2s            (6 cases on each engine)
ok   checkout-smoke PRODUCT_DEFECT → FAILED in 43.4s  (cart-total=FAILED/ASSERTION on each engine)
ok   checkout-smoke SELECTOR_DRIFT → FAILED in 49.4s  (SELECTOR_NOT_FOUND on each engine)
ok   checkout-smoke TIMEOUT → TIMED_OUT in 135.0s     (TEST_TIMEOUT on each engine)
ok   catalog-regression PASS → PASSED in 18.1s        (12 cases on each engine)
e2e OK
```

### AC-4 live (`make kill-check`)

```text
target RUNNING; sending SIGKILL to the worker container
waiting 35 s for the 30 s lease to run out (no heartbeats now)
run status: RUNNING | target status: RUNNING | finishedAt: None
attempt 1 RUNNING worker worker-1 leasedUntil 2026-11-20T11:45:32.593076Z leaseExpired True
 attempt | target  |   run   | lease_expired | expired_for_s | case_results
---------+---------+---------+---------------+---------------+--------------
 RUNNING | RUNNING | RUNNING | t             |            10 |            0
kill-check OK
```

### FR-6 live (`make sigterm-check`)

```text
target RUNNING; docker compose stop worker (SIGTERM)
worker container stopped after 21 s
run INFRA_ERROR | target INFRA_ERROR | attempt INFRA_ERROR WORKER_SHUTDOWN | detail: worker shut down before the attempt finished | cases 0
"msg":"stopping after the current attempt"
"msg":"shutdown: waiting for the current attempt"
"msg":"attempt finished; reporting"
"msg":"completion accepted"
"msg":"worker stopped claiming"
"msg":"worker exited cleanly"
sigterm-check OK
```

### Claim query plan (`ClaimExplainTest`, 5,000 targets, 10 `QUEUED`, ANALYZEd; excerpt)

```text
Limit  (actual time=0.030..0.031 rows=1.00 loops=1)
  ->  LockRows  (actual time=0.030..0.030 rows=1.00 loops=1)
        ->  Sort  Sort Key: t.queued_at, t.id   (over the 10 eligible rows only)
              ->  Index Scan using run_targets_queued on run_targets t  (actual time=0.004..0.006 rows=10.00 loops=1)
                    Index Cond: (status = 'QUEUED'::text)
Execution Time: 0.043 ms
```

Full plan: `docs/evidence/raw/prd-03-explain-claim-next.txt`.

## Negative / failure tests

- **Forged or wrong callers:** no token or a wrong token → `401` (`InternalPortTest`); `/internal/*` on the public port and public paths on the internal port → `404`, the same as an unknown path; wrong lease token → `410`.
- **Late and duplicate callbacks:** stale lease, `ABANDONED` attempt, duplicate key, different key, start or heartbeat after the end (`409 attempt-not-active`); terminal rows can't be changed even by direct SQL (trigger tests).
- **Malformed results:** AC-5 list above; truncated, empty or foreign report JSON → `ReportError` → `INFRA_ERROR / UNKNOWN` (`report.test.ts`, `attempt.test.ts`).
- **Process faults:** a CLI that ignores `SIGTERM` and leaves a grandchild → `SIGKILL` after the grace, nothing left running; a CLI that exits leaving an orphan → group swept; a crash without a report → `INFRA_ERROR / UNKNOWN` (`run-playwright.test.ts`, `attempt.test.ts`, fake CLIs).
- **Injection attempts:** case keys `.*`, `cart-total|.*`, `--headed`, `--grep=.*`, `cart-total; rm -rf /`, `$(id)`, spec path `suites/../../etc/passwd`, relative output dir → `ArgumentError`, nothing spawned (`playwright-args.test.ts`).
- **Revision drift:** a claim for another revision → `INFRA_ERROR / CONFIG`, nothing spawned.
- **Worker death and shutdown:** live `SIGKILL` (AC-4) and `SIGTERM` (FR-6) above; heartbeat `410` → run stopped, no completion sent; cancel → `CANCELLED`; `5xx` on complete → retried with the same key.
- **Environment fault found for real:** Firefox launch failure in the container → `INFRA_ERROR / BROWSER_LAUNCH` with 0 cases (never `PASSED`), then fixed.

## Security and threat check

- **Input restrictions:** worker API bodies are strictly parsed (unknown fields, wrong types → `400`); `workerId`, `leaseToken`, `completionKey`, case keys have patterns; ≤ 500 case results; error text ≤ 2,000 characters; the 64 KB body cap applies to both ports. The worker validates the claim with zod and builds every Playwright argument from its own manifest (ADR-003). Playwright gets `shell: false` and an allowlisted environment, and the service token is not in it (`run-playwright.test.ts`).
- **Tokens:** the service token is compared as sha256 hashes with `MessageDigest.isEqual`; the lease token is 32 `SecureRandom` bytes, stored only as sha256 (test checks no column contains it). Neither is logged: `LogCorrelationTest` (API, including the rejection paths), `worker.test.ts` (worker, including formatted messages), and a scan of 249 live API and worker log lines: 0 occurrences of the token, 0 lease-token-shaped strings, 0 `Bearer` values. Error text is redacted on both sides before storage.
- **New attack surface and mitigation:**
  - The worker API on port 8081 is not published. It is reachable only inside Compose and needs the service token. The API container sits on both `edge` and `internal`, so 8081 is also reachable from the `web` container on `edge` (still token-protected). Binding it to the internal network only is a PRD-12 item.
  - The worker container: non-root `pwuser`, `cap_drop: ALL`, `no-new-privileges`, read-only root, tmpfs `/tmp` and `HOME`, 1.5 GiB, 1.5 CPU, 512 pids, `internal` network only, no Docker socket.
  - Browser sandboxes are off or degraded in the container (Firefox user-namespace `EPERM`; Chromium `--no-sandbox` is Playwright's default). The container is the boundary, and only checked-in suites run against the allowlisted shop. This is documented, not hidden.
- **Secrets in the repo:** only the fake `TESTGRID_WORKER_TOKEN=local-dev-only-worker-token-not-a-secret` in `.env.example`; the recorded reports had local paths replaced before being committed.

## Skipped or mocked tests

- `worker/test/worker.test.ts` runs the loop against a **fake HTTP worker API** (real `WorkerApiClient`, real executor) and a **fake Playwright CLI** that replays recorded reports. `attempt.test.ts` and `run-playwright.test.ts` also use fake CLIs, so they need no browser or shop.
- The report parser is tested against **recorded real** 1.65.1 output, not hand-written JSON.
- AC-3 and AC-5 are proven at integration level (real HTTP, real Postgres), not against the Compose stack.
- The `PRD-04` reaper's effect (`ABANDONED`, requeue) is simulated by direct SQL in two tests to check that late callbacks are refused and that results list attempts newest first.
- **CI not run yet:** the branch is not pushed. The CI workflow now runs `make e2e` (Chromium) after `make up`; its first run will be the first proof on `ubuntu-24.04` / amd64.

## Screenshots / demo / raw data links

- `docs/evidence/raw/prd-03-e2e-chromium.txt`, `prd-03-e2e-three-engines.txt`: AC-1, AC-2, AC-6.
- `docs/evidence/raw/prd-03-kill-check.txt`: AC-4. `prd-03-sigterm-check.txt`: FR-6.
- `docs/evidence/raw/prd-03-firefox-home-before-fix.txt`: the Firefox platform finding.
- `docs/evidence/raw/prd-03-api-tests.txt`, `prd-03-skip-locked-mutation.txt`, `prd-03-explain-claim-next.txt`.
- `worker/test/fixtures/playwright-1.65.1/`: recorded reports.

## Known limitations and deferred improvements

- **No recovery yet:** an expired lease stays visible but nothing reclaims it; the reaper, retries and `ABANDONED` transitions are PRD-04.
- **One worker, one slot:** a three-engine run executes its targets one after another; leftover `QUEUED` targets from earlier sessions are processed first (FIFO).
- **Suite revision still ignores `suites/support/*.ts`** (N10, deferred, needs an ADR).
- **Claim order sorts the eligible queued rows** (`Sort` over `run_targets_queued` hits). Fine at this scale; the PRD-04 dispatcher takes over ordering.
- **A run without a fixture still uses the shop's fixture session** (PASS, seed 42), because the spec support always creates one.
- **Runs whose targets name an engine no worker serves stay `QUEUED`**, and the run stays `RUNNING`. Honest, but there is no "no capable worker" signal until the PRD-04 worker registry.
- **The worker sends no completion after a lost lease**, and a worker killed by `SIGKILL` outside a container would leave its Playwright group running (inside Compose, the container's death kills the group).
- **Memory use per engine was not measured**; the 1.5 GiB limit held for all runs above, but there is no peak figure yet.
- **API log format is now JSON everywhere**, including `mvn test` output; there is no plain-text toggle.
- **`traceparent` from the worker is accepted per request**, but the API doesn't yet store or forward it beyond logging (outbox in PRD-04).

## Explain-back review (developer's own words)

> To be answered by the developer. PRD-03's definition of done requires that "the developer can explain SKIP LOCKED and the lease check without AI". The PRD-01 and PRD-02 explain-backs are also still owed.

1. The three most important engineering decisions in this PRD, and why:
   - Why does `claims/next` use `FOR UPDATE SKIP LOCKED`, and what would change (and what wouldn't) with plain `FOR UPDATE`? Use the mutation result above.
   - Why is the lease checked against the database's `now()` and the token stored only as a hash, and why does the terminal check come before the expiry check?
   - Why does the API compute the target status from the case results instead of trusting the worker's status?
2. What happens if the main operation fails halfway through? Walk through: the worker is killed after `start` but before `complete`; the completion commits but its `200` is lost; the API dies in the middle of the completion transaction.
3. What would you change for production?

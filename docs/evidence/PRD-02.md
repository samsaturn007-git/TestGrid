# Evidence — PRD-02

**PRD:** `prds/PRD-02-domain-and-api.md`  
**Branch / commits:** `prd-02/domain-api` — `71528a1` (start), `367e8b9` (setup), `5371c80` (T1), `b7cea96` (T2), `ad8a0e1` (T3), `414dbe9` (T4), `ce7115d` + `d38e90b` (T5), `76ffa4e` (T6); tracker ticks `adc18c4`, `b10490f`, `bd87d89`, `ad1712d`, `2930a36`. Not pushed yet.  
**Reviewer:** developer (repository owner). Review pending.  
**Date:** 2026-11-19 → 2026-11-20 (UTC)  
**Outcome:** pass (all six ACs verified locally; awaiting developer review and the first GitHub Actions run of this branch)

All commands ran on the reference machine (Apple M4, 16 GiB, macOS 27.2.1, OrbStack / Docker 29.8.2, Compose 5.5.0, JDK 21.0.13.1, Node 26.13.1, pnpm 12.21.0). Every API test is **live** against PostgreSQL 18.7 in Testcontainers (`postgres:18.7-bookworm`, the Compose image) unless marked otherwise. No paid service and no credential was used.

## Acceptance criteria with evidence

| Criterion | Pass? | Test ID(s) | Proof (file, test and observable behaviour) |
|---|---|---|---|
| AC-1 | Yes | API-006 | `RestartPersistenceTest.api006RunAndTargetsSurviveAnApiRestart`: `POST` → `202`, then a second full API process on the same database returns identical JSON for the run and both targets; the same key replays `200`. Live: `make restart-check` creates a run, runs `docker compose restart api`, compares the JSON and replays the key ("restart-check OK", output below). Now also a CI step. |
| AC-2 | Yes | API-001, API-002, API-004 | `IdempotencyApiTest` (same key+payload → same ID, `200` + `Idempotent-Replay: true`; different payload → `409 idempotency-conflict`; DB holds 1 run, 2 targets, 1 event). `ConcurrentIdempotencyTest` ×3 repeats: 10 parallel HTTP POSTs → one `202`, nine `200`, one run; mixed bodies → one `202`, four `200`, five `409`, one run. `IdempotencyRaceTest` holds the first insert open until `pg_stat_activity` shows the second `INSERT` blocked on its lock, then commits: the blocked request replays, or `409`s when its body differs. With `ON CONFLICT` removed, all 8 concurrency tests fail. |
| AC-3 | Yes | API-003 | `CreateRunApiTest`: unknown suite → `422 unknown-suite`; engine outside `allowedEngines` → `422 engine-not-allowed`; mode the suite doesn't declare → `422 fixture-not-allowed`; unknown field (top-level and nested) → `400 validation-failed` naming the field; 201-character branch → `400`; URL-like `branch` / `ciRef` → `400`. `FixtureOutsideLocalProfileTest`: any fixture with `TESTGRID_PROFILE` unset → `422 fixture-not-allowed`. Every rejection asserts 0 runs, 0 targets and 0 events afterwards. |
| AC-4 | Yes | API-005 | `RunQueryApiTest.api005PagingWhileFiftyRunsAreInsertedHasNoDuplicatesOrGaps`: 50 runs over 10 timestamps (ties broken by `id`), paged 7 at a time while 50 more are created through the API between pages; 8 pages, result equals the original 50 in order, no duplicates. `RunListExplainTest` (5,000 runs, `ANALYZE`d): both page queries are an `Index Scan using runs_project_queued_at_id` with the row comparison as the index condition and no sort (plan below and in `docs/evidence/raw/prd-02-explain-runs-list.txt`). |
| AC-5 | Yes | — | `RunAggregateTest`: all 1,463 combinations of the 11 reachable target states (status × with/without attempt) for 1–3 targets, plus all 584 plain status combinations (8 + 64 + 512), against an independently written oracle; 18 readable example rows; empty input rejected. |
| AC-6 | Yes (local) | — | Renaming `RunSummary.targetCount` → `targetTotal` without regenerating makes `make contracts-check` exit 2 with the diff in `api/openapi.yaml` and `packages/contracts/src/api.ts`; reverting it exits 0 (`docs/evidence/raw/prd-02-contract-drift.txt`). `make contracts-check` is the exact CI step (`.github/workflows/ci.yml`). The first GitHub run of it happens when this branch is pushed. |

## Tasks completed

| Task | Commit(s) | Notes |
|---|---|---|
| Setup | `71528a1`, `367e8b9` | PRD-02 started; JDBC, Flyway (Boot BOM 12.4.0), pgjdbc, Testcontainers 2.0.5; API connected to Compose Postgres; manifest gap recorded as CONFLICT_REGISTER N7 and ruled by the developer. |
| T1 | `5371c80` | `V1__core.sql` (six tables, contracts §3 constraints and indexes, named checks, append-only trigger on `run_events`), `V2__local_project.sql`; `worker/manifest/suites.json` + `packages/contracts/schema/suite-manifest.schema.json`; `ManifestLoader` (schema + semantic checks + revision), `ManifestSeeder` (advisory lock, one current revision per suite key). API image now built from the repo root with the manifest and specs copied in. |
| T2 | `b7cea96` | `POST /api/v1/projects/{projectId}/runs`: Bean Validation, strict JSON, 64 KB cap, engine allowlist, fixture-profile guard, one transaction for run + `local:<engine>:0` targets + `RUN_CREATED`; Problem Details advice. |
| T3 | `ad8a0e1` | Canonical request hash, `INSERT … ON CONFLICT DO NOTHING`, replay `200` / `409`; fixed relative manifest path under a real servlet container. |
| T4 | `414dbe9` | `RunAggregate`, `GET /runs` (keyset cursor), `GET /runs/{runId}`, FR-3 catalog endpoints, `CallerScope` ownership seam. |
| T5 | `ce7115d`, `d38e90b` | `traceId` / `X-Request-Id` / `traceparent`, springdoc → `api/openapi.yaml` (OpenAPI 3.1), `@testgrid/contracts` with openapi-typescript, `make contracts` / `make contracts-check`, CI step. |
| T6 | `76ffa4e` | Restart persistence and atomicity tests; `make restart-check` (also in CI). The suite as a whole: 131 Testcontainers/unit tests (below). |

## Changed files

- `api/src/main/resources/db/migration/V1__core.sql`, `V2__local_project.sql`: schema and the fixed local project (FR-1, FR-10).
- `api/src/main/java/dev/testgrid/api/manifest/*`: manifest loading, validation, revision and seeding (FR-2).
- `api/src/main/java/dev/testgrid/api/run/*`: create (FR-4, FR-5), list and detail (FR-6, FR-7), cursor, request hash, DTOs.
- `api/src/main/java/dev/testgrid/api/catalog/*`: projects and suites (FR-3).
- `api/src/main/java/dev/testgrid/api/domain/*`: canonical enums and `RunAggregate` (contracts §2).
- `api/src/main/java/dev/testgrid/api/problem/*`: Problem Details, correlation filter, body limit (FR-8).
- `api/src/main/java/dev/testgrid/api/config/*`: strict JSON, profile, OpenAPI shape (FR-9).
- `api/src/main/java/dev/testgrid/api/project/*`, `common/*`: `CallerScope`, `LocalProject`, canonical JSON, hashing, `Page`, `@ApiNullable`.
- `api/openapi.yaml`, `packages/contracts/{package.json,tsconfig.json,src/api.ts,schema/*,README.md}`: committed contracts.
- `api/src/test/java/dev/testgrid/api/**`: 21 test classes (list below).
- `api/pom.xml`, `api/Dockerfile` (root build context), `infra/compose/compose.yaml` (DB env, `TESTGRID_PROFILE`), `.env.example`, `.gitattributes` (LF for hashed files), `Makefile` (`contracts`, `contracts-check`, `restart-check`), `scripts/contracts-check.sh`, `scripts/restart-check.sh`, `.github/workflows/ci.yml`, `.prettierignore`, `eslint.config.mjs`, `pnpm-lock.yaml`.
- Docs: `docs/API_AND_DATA_CONTRACTS.md` (§1 correlation and spec, §2.3 "ever claimed", §3 suites columns and invariants, §4 validation, idempotency and read endpoints, §6, §10 revision algorithm), `docs/VERSIONS.md` §1.2, `docs/CONFLICT_REGISTER.md` N7, `CONTRIBUTING.md` (committed generated contract files).

## Design notes (for review)

- **Idempotency belongs to the database.** `UNIQUE(project_id, idempotency_key)` decides the winner. Under READ COMMITTED a second `INSERT … ON CONFLICT DO NOTHING` for the same key waits for the first transaction: if it commits, the loser reads that run and compares the stored sha256 request hash (`200` replay or `409`); if it rolls back, the loser inserts. A known key is answered before any manifest check, so a CI retry still replays after the suite's revision changed.
- **Keyset pagination.** Order `(queued_at DESC, id DESC)`, next page `WHERE (queued_at, id) < (:cursor)`, read `limit + 1` rows to know if there is a next page. Each page is an index range scan of at most 101 rows however deep the client goes. New runs get a larger `queued_at`, so they land before page 1 and can't duplicate or shift rows on later pages. The cursor stores microseconds, Postgres's exact precision, so there's no rounding gap.
- **Invariants are DDL.** Canonical spellings, terminal-implies-`finished_at`, a run's suite belongs to its project (composite FK), one current revision per suite key (partial unique index), append-only `run_events` (trigger). `SchemaConstraintsTest` writes straight to the tables to prove each holds without the API.
- **One aggregate rule.** `RunAggregate.of` is pure and exhaustively tested. Detail computes it from the targets on every read; `runs.status` stores it for listing, and later writers must update it in the same transaction (contracts §2.3).
- **Ownership seam.** `CallerScope` is the only place that decides which project a request sees. Everything outside it is `404`, exactly like something that doesn't exist.

## Actual executed commands and outputs

### Tests (`make check`, `make test`)

```text
$ make check; echo "make check exit=$?"
make check exit=0          # prettier, eslint --max-warnings=0, tsc ×4 (incl. packages/contracts), spotless:check compile
$ make test; echo "make test exit=$?"
make test exit=0
worker test:    ℹ tests 5   ℹ pass 5   ℹ fail 0
demo-shop test: ℹ tests 42  ℹ pass 42  ℹ fail 0
$ bash tools/vocab_check.sh
vocab_check OK
$ python3 tools/progress.py lint
lint OK: tracker matches PRD files
```

API surefire totals (`docs/evidence/raw/prd-02-api-tests.txt`):

```text
catalog.CatalogApiTest                        tests=4   errors=0 skipped=0 failures=0
common.CanonicalJsonTest                      tests=3   errors=0 skipped=0 failures=0
contract.OpenApiSpecTest                      tests=1   errors=0 skipped=0 failures=0
db.SchemaConstraintsTest                      tests=14  errors=0 skipped=0 failures=0
domain.RunAggregateTest                       tests=21  errors=0 skipped=0 failures=0
health.HealthControllerTest                   tests=2   errors=0 skipped=0 failures=0
manifest.InvalidManifestStartupTest           tests=2   errors=0 skipped=0 failures=0
manifest.ManifestLoaderTest                   tests=17  errors=0 skipped=0 failures=0
manifest.ManifestSeedingTest                  tests=5   errors=0 skipped=0 failures=0
problem.ProblemDetailsTest                    tests=7   errors=0 skipped=0 failures=0
run.ConcurrentIdempotencyTest                 tests=6   errors=0 skipped=0 failures=0
run.CreateRunApiTest                          tests=18  errors=0 skipped=0 failures=0
run.FixtureOutsideLocalProfileTest            tests=2   errors=0 skipped=0 failures=0
run.IdempotencyApiTest                        tests=6   errors=0 skipped=0 failures=0
run.IdempotencyRaceTest                       tests=2   errors=0 skipped=0 failures=0
run.RestartPersistenceTest                    tests=1   errors=0 skipped=0 failures=0
run.RunCreationAtomicityTest                  tests=2   errors=0 skipped=0 failures=0
run.RunCursorTest                             tests=7   errors=0 skipped=0 failures=0
run.RunListExplainTest                        tests=1   errors=0 skipped=0 failures=0
run.RunQueryApiTest                           tests=7   errors=0 skipped=0 failures=0
run.RunRequestHashTest                        tests=3   errors=0 skipped=0 failures=0
TOTAL                                         tests=131 errors=0 skipped=0 failures=0
```

### Concurrency tests can fail (T3 mutation check)

```text
# ON CONFLICT (project_id, idempotency_key) DO NOTHING deleted from RunRepository, then:
$ ./mvnw -B -q test -Dtest='IdempotencyRaceTest,ConcurrentIdempotencyTest'
[ERROR] Tests run: 2, Failures: 1, Errors: 1 ... in dev.testgrid.api.run.IdempotencyRaceTest
[ERROR] Tests run: 6, Failures: 0, Errors: 6 ... in dev.testgrid.api.run.ConcurrentIdempotencyTest
[ERROR] Tests run: 8, Failures: 1, Errors: 7
# restored; the same 8 pass
```

### Demo moment (live, Compose)

```text
--- POST #1
HTTP/1.1 202
id 28add726-b13d-4e7a-869e-893611921e96
--- POST #2   (same key, same body)
HTTP/1.1 200
Idempotent-Replay: true
id 28add726-b13d-4e7a-869e-893611921e96
--- POST #3   (same key, firefox -> webkit)
{"detail":"idempotency key demo-t3-1795126166 was already used for run 28add726-b13d-4e7a-869e-893611921e96 with a different request",
 "status":409,"title":"Idempotency key reused with a different body","type":"https://testgrid.local/problems/idempotency-conflict",
 "errors":[{"field":"idempotencyKey","message":"already used with a different request"}], ...}
HTTP 409
```

### AC-1 live restart (`make restart-check`, `docs/evidence/raw/prd-02-restart-check.txt`)

```text
created run c68688d2-b68b-43be-b9d7-a385c688e674 (HTTP 202)
restarting the api container...
api healthy again after 6s
run c68688d2-b68b-43be-b9d7-a385c688e674 unchanged after restart: status QUEUED, targets ['local:chromium:0', 'local:firefox:0']
same key after restart: HTTP 200, Idempotent-Replay: true, same run id
restart-check OK
# api log after the restart:
Schema "public" is up to date. No migration necessary.
manifest suite checkout-smoke revision 29d6afdd879b (unchanged)
```

### AC-4 `EXPLAIN (ANALYZE, BUFFERS)` over 5,000 runs (excerpt; full plans in `docs/evidence/raw/prd-02-explain-runs-list.txt`)

```text
-- GET /runs first page (limit 20 + 1)
Limit  (actual time=0.013..0.025 rows=21.00 loops=1)
  ->  Index Scan using runs_project_queued_at_id on runs r  (actual time=0.004..0.005 rows=21.00 loops=1)
        Index Cond: (project_id = '00000000-0000-4000-8000-000000000001'::uuid)
Execution Time: 0.039 ms
-- GET /runs page after a cursor in the middle
Limit  (actual time=0.009..0.024 rows=21.00 loops=1)
  ->  Index Scan using runs_project_queued_at_id on runs r  (actual time=0.003..0.007 rows=21.00 loops=1)
        Index Cond: ((project_id = '…0001'::uuid) AND (ROW(queued_at, id) < ROW('2026-11-20 15:17:43.707756+05:30'::timestamp with time zone, '852cfd65-…'::uuid)))
```

No `Sort` node and no `Seq Scan on runs` in either plan (asserted by the test).

### AC-6 contract drift (`docs/evidence/raw/prd-02-contract-drift.txt`)

```text
## 1. Deliberate DTO change: rename RunSummary.targetCount -> targetTotal (no regeneration)
$ make contracts-check
-        targetCount:
+        targetTotal:
-            targetCount: number;
+            targetTotal: number;
contract drift: the code no longer matches the committed contract files.
make contracts-check exit=2
## 2. Revert the DTO change
$ make contracts-check
contracts-check OK: api/openapi.yaml and packages/contracts/src/api.ts match the code
make contracts-check exit=0
```

### Startup with an invalid manifest (FR-2)

```text
InvalidManifestStartupTest: SpringApplication.run throws InvalidManifestException:
  …/manifest/suites.json does not match the manifest schema (1 errors):
    /suites/0/playwrightRetries: must have a maximum value of 3
and no suite row is written.
```

## Negative / failure tests

- **Hostile create requests (API-003):** unknown suite, engine, fixture mode, unknown fields at any depth, oversized strings, URL-like strings, duplicate JSON keys, `"42"` for a number, a number for an enum, body > 64 KB, `BROWSERSTACK`, `RERUN`, sharding, duplicate targets, unknown or non-UUID project. Each returns its Problem type and leaves 0 rows.
- **Concurrent duplicates:** injected with 10 parallel HTTP requests (×3) and a deterministic held-open transaction; exactly one run each time.
- **Partial failure:** `RunCreationAtomicityTest` makes the last write (`RUN_CREATED`) throw through a Mockito spy on the repository; the request is a generic `500` and the run and target rows are rolled back. The same request then succeeds, because a failed create doesn't hold the key.
- **Restart:** a full second API process (test) and `docker compose restart api` (live); data and the idempotency record survive, Flyway is a no-op, the seeder reports `unchanged`.
- **Bad manifests:** malformed JSON, duplicate keys, unknown field, unknown engine, URL as `baseUrlRef`, `..` in a spec path, a symlink escaping `suites/`, missing spec file, duplicate suite/case keys, tag ≠ `@caseKey`; the API refuses to start.
- **Schema bypass:** `SchemaConstraintsTest` writes invalid rows directly (non-canonical status, terminal without `finished_at`, foreign suite, second current revision, unknown engine/event, `UPDATE`/`DELETE` on `run_events`); all rejected by Postgres.
- **Error leakage:** a test-only endpoint throws an exception containing a fake password and class name; the `500` body contains neither, and no stack trace.

## Security and threat check

- **Input restrictions:** clients choose only enum values and manifest keys (ADR-003). Every free-text field has a pattern that excludes URL schemes, `..`, spaces and shell syntax; strings ≤ 200 characters; body ≤ 64 KB; unknown fields and duplicate keys rejected; no coercion. The manifest comes only from the image's read-only copy; spec paths are resolved to real paths under `worker/suites/`, so symlinks can't escape.
- **Headers:** `traceparent` is accepted only in the exact W3C level-1 format; `X-Request-Id` is echoed only if it matches `[A-Za-z0-9._-]{1,64}`, otherwise replaced, so it can't inject header or log content.
- **Errors:** generic `500` body, internals only in the log; `server.error.include-*` set to never for anything outside MVC.
- **Secrets:** none. The DB password is the fake `.env` value passed by Compose; nothing logs request bodies.
- **New attack surface and mitigation:** the API now has an unauthenticated write endpoint (`POST /api/v1/projects/{projectId}/runs`) and `/v3/api-docs`, both on `127.0.0.1:8080` only. Authentication is planned (service token PRD-03, CI token PRD-08, user tokens PRD-12); `CallerScope` is the seam it plugs into. Fixture modes are refused unless `TESTGRID_PROFILE` is exactly `local`, and the API's own default is non-local.
- **Database privilege:** the API connects as the database owner, so it could in principle disable the append-only trigger or run DDL. A least-privilege runtime role is a PRD-12 hardening item.
- **Canary scan:** not applicable yet (no secrets or artifacts); SEC-002 starts in PRD-06.

## Skipped or mocked tests

- `RunCreationAtomicityTest` uses a Mockito spy only to inject the fault; the database and transaction are real.
- Everything else is live: real PostgreSQL 18.7 (Testcontainers), real HTTP for `ConcurrentIdempotencyTest` and `RestartPersistenceTest`, real Compose for `make restart-check` and the demo.
- The chunked-body path of the 64 KB limit (no `Content-Length`) has no dedicated test; only a declared length over 64 KB is tested.
- GitHub Actions has not run this branch yet (not pushed). The new CI steps (`make contracts-check`, `make restart-check`) have only been run locally.

## Screenshots / demo / raw data links

- `docs/evidence/raw/prd-02-api-tests.txt`: per-class surefire totals.
- `docs/evidence/raw/prd-02-explain-runs-list.txt`: full `EXPLAIN (ANALYZE, BUFFERS)` for both page queries.
- `docs/evidence/raw/prd-02-contract-drift.txt`: AC-6 change and revert.
- `docs/evidence/raw/prd-02-restart-check.txt`: AC-1 against Compose.
- `api/openapi.yaml`, `packages/contracts/src/api.ts`: the generated contracts.

## Known limitations and deferred improvements

- **Suite revision covers only the listed spec files.** `checkout-smoke.spec.ts` imports `suites/support/*.ts`; editing those helpers changes behaviour without changing the revision. PRD-03 (worker manifest loader) should list or hash the support files too.
- **`runs.status` is stored and also computed.** They agree today because nothing moves targets yet; PRD-03 writers must update both in one transaction (contracts §2.3).
- **Cursors aren't signed.** A forged but well-formed cursor only moves the client's own page position; an unparsable one is `400`.
- **No DB readiness in `/healthz`.** It is liveness only; PRD-01's note expected readiness here, and it remains open.
- **Fixture modes and other enums are declared in several places** (Java enums, JSON Schema, demo-shop, worker). The generated TS types now cover the API side; the worker should import from `@testgrid/contracts` in PRD-03.
- **`openapi-typescript` 7.13.0 declares TypeScript 5** and prints an unmet-peer warning; output verified under 6.0.3 (`docs/VERSIONS.md` §1.2).
- **Flyway is the Boot-managed 12.4.0**, not Maven Central's 13.13.0, to keep Boot's tested combination.
- **No downstream propagation of `traceparent` yet** (no outgoing calls exist); the outbox stores it from PRD-04.
- **Log lines outside a request show `[ ]`** for the empty trace and request IDs (cosmetic).
- **`GET /runs` filters** (`status`, `suiteKey`, `branch`, `trigger`) are PRD-05; only `limit` and `cursor` exist.

## Explain-back review (developer's own words)

PRD-02's definition of done includes: "the developer can explain the idempotency and pagination design without AI." Please answer these yourself before closing the PRD; they are deliberately not pre-filled.

1. **The three most important engineering decisions in this PRD, and why.** Suggested topics: why the unique constraint plus `ON CONFLICT DO NOTHING` (not a "check, then insert" in Java) is what makes API-004 hold; why keyset pagination on `(queued_at, id)` can't skip or repeat rows while runs are inserted, and why `id` is needed; why the invariants live in the schema rather than only in the service.

   _To be written by the developer._

2. **What happens if the main operation fails halfway through?** For example: the database connection drops after the run row is inserted but before `RUN_CREATED`; or two identical requests arrive and the first one's transaction rolls back.

   _To be written by the developer._

3. **What would you change for production?**

   _To be written by the developer._

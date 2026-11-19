# API and Data Contracts — v2 (planning baseline)

**Status:** Proposed. This is the single source of truth for the project's vocabulary: statuses, error types, events and table names. PRD-02 publishes `api/openapi.yaml` from the implemented code. Any change to a shape updates this file, the OpenAPI spec, the generated client types and the tests in the same PR (AGENTS.md rule 5).

Each section names the PRD that introduces it. Codex implements only the parts owned by the active PRD.

---

## 1. Conventions

- **Surfaces**
  - `/api/v1/*` is the public API, used by the dashboard, CLI and CI.
  - `/internal/v1/*` is the worker API. It runs on a separate port that is never published on public ingress, and it requires a service token (PRD-03).
- **IDs:** UUID primary keys. `run_events` and `outbox_events` use `bigserial` IDs so they can be ordered and replayed.
- **Time:** `timestamptz` in UTC, ISO-8601 on the wire. Lease and expiry checks always use the database's `now()`, never the worker's clock, which can drift.
- **Errors:** RFC 9457 Problem Details (`application/problem+json`): `type`, `title`, `status`, `detail`, `instance`, `traceId`, `errors[] {field, message}`. The type catalogue is in §6.
- **Pagination**
  - Query: `?limit=1..100` (default 20) `&cursor=<opaque>`.
  - Response: `{ "items": [...], "nextCursor": "..." | null }`.
  - Keyset ordering on `(queued_at DESC, id DESC)`. Offset pagination is not used.
- **Limits:** JSON body ≤ 64 KB, and strings ≤ 200 characters unless a field says otherwise. Unknown JSON fields are rejected on write endpoints.
- **Correlation:** accept and propagate W3C `traceparent`. Every response carries `X-Request-Id`, and every Problem body carries `traceId`.
- **Never accepted from clients:** URLs, file paths, repository links, shell fragments, JavaScript, regexes or Playwright CLI flags. Clients choose only from enums and manifest keys.

---

## 2. Canonical status model

### 2.1 Enums
| Enum | Values | Introduced |
|---|---|---|
| `RunStatus` | `QUEUED, RUNNING, PASSED, FAILED, TIMED_OUT, INFRA_ERROR, CANCELLED` | PRD-02 |
| `TargetStatus` | `QUEUED, CLAIMED, RUNNING, PASSED, FAILED, TIMED_OUT, INFRA_ERROR, CANCELLED` | PRD-02 |
| `AttemptStatus` | `CLAIMED, RUNNING, PASSED, FAILED, TIMED_OUT, INFRA_ERROR, CANCELLED, ABANDONED` | PRD-03 |
| `CaseStatus` | `PASSED, FAILED, TIMED_OUT, SKIPPED, FLAKY` (`FLAKY` = passed after an in-run Playwright retry) | PRD-03 |
| `QualityOutcome` | `PASS, FAIL, INDETERMINATE` | PRD-08 |
| `ErrorClass` | `ASSERTION, TEST_TIMEOUT, SELECTOR_NOT_FOUND, BROWSER_LAUNCH, WORKER_LOST, WORKER_SHUTDOWN, LEASE_EXPIRED, NETWORK, ARTIFACT_STORE, CONFIG, CANCELLED, UNKNOWN` | PRD-03 |
| `FailureCategory` | `PRODUCT_SUSPECTED, TEST_BROKEN, AUTOMATION_FLAKE, TIMEOUT, ENVIRONMENT, UNKNOWN` | PRD-07 |
| `FixtureMode` | `PASS, PRODUCT_DEFECT, AUTOMATION_FLAKE, SELECTOR_DRIFT, TIMEOUT` | PRD-01 |
| `Trigger` | `MANUAL, CI, RERUN, BENCHMARK, CHAOS` | PRD-02 |
| `Executor` | `LOCAL, BROWSERSTACK` | PRD-02 (`BROWSERSTACK` used from PRD-09) |
| `Engine` | `chromium, firefox, webkit` | PRD-02 |
| `ShardingMode` | `NONE, PLAYWRIGHT_NATIVE, DURATION_BALANCED` | PRD-11 (`NONE` before then) |

Infrastructure faults such as a browser failing to launch or a worker being killed are not demo-shop modes. They are injected through the chaos hooks (PRD-10) and the worker's fault flags.

### 2.2 Target and attempt transitions
| From → To | Trigger | Attempt effect |
|---|---|---|
| `QUEUED → CLAIMED` | claim succeeds | new attempt `CLAIMED`, `attempt_no = previous + 1` |
| `CLAIMED → RUNNING` | worker calls `start` | attempt `RUNNING` |
| `RUNNING → PASSED / FAILED / TIMED_OUT` | `complete` with valid lease | attempt gets the same status |
| `CLAIMED / RUNNING → INFRA_ERROR` | worker reports infra failure, or the final retry is exhausted | attempt `INFRA_ERROR` or `ABANDONED` |
| `CLAIMED / RUNNING → QUEUED` | lease expires and retries remain (reaper) | attempt `ABANDONED` (`LEASE_EXPIRED`); `not_before` set by backoff |
| `QUEUED → CANCELLED` | run cancel requested | none |
| `CLAIMED / RUNNING → CANCELLED` | worker sees `cancelRequested` and completes with `CANCELLED`, or the lease expires while a cancel is requested | attempt `CANCELLED` or `ABANDONED` |

Rules:
- **Terminal states never change.** A transition request that arrives late is rejected with a Problem response and logged, never silently applied.
- **Target status is computed by the API from validated case results.** The worker's self-reported status is used only when there are no case results (infra failure, timeout, cancel).
  - Any case `FAILED` → `FAILED`.
  - Otherwise any case `TIMED_OUT` → `TIMED_OUT`.
  - Otherwise every case `PASSED`, `FLAKY` or `SKIPPED` → `PASSED`.
  - Zero cases executed → `INFRA_ERROR` (`CONFIG`).

### 2.3 Run aggregate (truth table, PRD-02 unit test)
- **Not all targets terminal:** the run is `RUNNING` if any target has ever been claimed, otherwise `QUEUED`.
- **All targets terminal:** the run takes the first match in this order: `FAILED > TIMED_OUT > INFRA_ERROR > CANCELLED > PASSED`.
- **Why this order:** a confirmed product failure is the most useful signal. Uncertain outcomes come next. `PASSED` requires every target to pass.

### 2.4 Quality outcome (PRD-08)
| Situation | Outcome | CLI exit |
|---|---|---|
| Every policy rule satisfied | `PASS` | 0 |
| Any blocking rule violated by test evidence (critical case failed, too many failures, duration regression over limit) | `FAIL` | 1 |
| A required target is missing, `INFRA_ERROR`, `TIMED_OUT` at target level, `CANCELLED`, or the run did not finish before the CLI deadline | `INDETERMINATE` | 2 |
| Bad configuration, authentication or usage | — | 3 |

A failure in a quarantined case does not count toward `FAIL`. It still appears in the rule report as `QUARANTINED_FAIL`.

---

## 3. Tables

Owner PRD in brackets. Every table has `created_at`. Mutable aggregates have a `version int` column for optimistic checks.

| Table | Key columns and constraints |
|---|---|
| `projects` [02] | `id`, `slug UNIQUE`, `name`, `owner_id NULL` |
| `suites` [02] | `id`, `project_id`, `suite_key`, `revision` (12 hex, algorithm in §10), `display_name`, `enabled`, `is_current`, `timeout_ms`, `playwright_retries`, `allowed_engines text[]`, `fixture_modes text[]`; `UNIQUE(project_id, suite_key, revision)`; **partial unique index `UNIQUE(project_id, suite_key) WHERE is_current`** (old revisions are kept, one is current); `UNIQUE(project_id, id)` so `runs` can reference `(project_id, suite_id)` |
| `test_cases` [02] | `id`, `suite_id`, `case_key`, `title`, `tag` (`@case-key`), `critical bool`, `expected_duration_ms NULL`; `UNIQUE(suite_id, case_key)` |
| `runs` [02] | `id`, `project_id`, `suite_id`, `status`, `trigger`, `fixture_mode NULL`, `fixture_seed NULL`, `commit_sha NULL`, `branch NULL`, `ci_ref NULL`, `idempotency_key`, `request_hash`, `sharding_mode`, `shard_count`, `cancel_requested_at NULL`, `parent_run_id NULL` (rerun link, PRD-05), `queued_at`, `started_at NULL`, `finished_at NULL`, `version`; `UNIQUE(project_id, idempotency_key)`; FK `(project_id, suite_id) → suites(project_id, id)`; `finished_at` is set exactly when `status` is terminal |
| `run_targets` [02] | `id`, `run_id`, `target_key` (e.g. `local:chromium:0`), `executor`, `engine`, `profile_key NULL`, `shard_index`, `shard_count`, `status`, `attempt_count`, `max_attempts`, `not_before NULL`, `dispatch_generation int`, `dispatched_at NULL`, `predicted_ms NULL`, `queued_at`, `finished_at NULL`, `version`; `UNIQUE(run_id, target_key)`; `finished_at` is set exactly when `status` is terminal |
| `run_events` [02] | `id bigserial`, `run_id`, `target_id NULL`, `attempt_id NULL`, `type`, `schema_version`, `payload jsonb`, `occurred_at`. Append-only (a trigger rejects `UPDATE` and `DELETE`); written in the same transaction as the state change it describes |
| `attempts` [03] | `id`, `target_id`, `attempt_no`, `worker_id`, `status`, `lease_token_hash bytea`, `leased_until`, `last_heartbeat_at`, `claimed_at`, `started_at NULL`, `finished_at NULL`, `error_class NULL`, `error_detail_redacted NULL`, `completion_key NULL`; `UNIQUE(target_id, attempt_no)`; **partial unique index `UNIQUE(target_id) WHERE status IN ('CLAIMED','RUNNING')`**, which makes the database enforce one active attempt per target |
| `case_results` [03] | `id`, `attempt_id`, `case_key`, `status`, `duration_ms`, `retry_count`, `error_class NULL`, `error_message_redacted NULL`, `fingerprint_id NULL`; `UNIQUE(attempt_id, case_key)`. Written only inside the completion transaction |
| `workers` [04] | `id`, `name`, `version`, `executor`, `engines text[]`, `slots`, `state ACTIVE / DRAINING / LOST`, `started_at`, `last_seen_at` |
| `outbox_events` [04] | `id bigserial`, `aggregate_type`, `aggregate_id`, `event_type`, `schema_version`, `payload jsonb`, `traceparent NULL`, `published_at NULL`, `publish_attempts`, `last_error NULL`; partial index `WHERE published_at IS NULL` |
| `scheduler_settings` [04] | single row: `global_max_sessions`, `policy FIFO / LPT_AGING` (`LPT_AGING` from PRD-11), `aging_weight`, `max_attempts`, `lease_ttl_s` |
| `artifacts` [06] | `id`, `attempt_id`, `case_result_id NULL`, `kind SCREENSHOT / TRACE / LOG`, `mime`, `storage_key` (generated by the server), `byte_size`, `sha256`, `state AVAILABLE / EXPIRED / MISSING / PURGED`, `expires_at` |
| `failure_fingerprints` [07] | `id`, `project_id`, `algo_version`, `fingerprint`, `category`, `confidence`, `rule_id`, `sample_message`, `first_seen_at`, `last_seen_at`, `occurrence_count`, `override_category NULL`, `override_reason NULL`, `overridden_by NULL`; `UNIQUE(project_id, algo_version, fingerprint)` |
| `case_health` [07] | `project_id`, `suite_key`, `case_key`, `engine`, `window_size`, `observed`, `pass_count`, `fail_count`, `flaky_count`, `flip_count`, `flip_rate`, `label STABLE / FLAKY / FAILING / INSUFFICIENT_DATA`, `computed_at`; PK `(project_id, suite_key, case_key, engine)` |
| `api_tokens` [08] | `id`, `name`, `token_hash`, `scopes text[]`, `expires_at`, `last_used_at`, `revoked_at NULL`. PRD-08 adds a CI token; PRD-12 adds user tokens and scopes |
| `quality_policies` [08] | `id`, `project_id`, `version`, `policy jsonb` (schema in §12), `active`; `UNIQUE(project_id, version)` |
| `quality_evaluations` [08] | `id`, `run_id`, `policy_id`, `outcome`, `rules jsonb`, `evaluated_at`; `UNIQUE(run_id, policy_id)` |
| `quarantines` [08] | `id`, `project_id`, `case_key`, `engine NULL`, `reason`, `source MANUAL / SUGGESTED / AUTO`, `created_by`, `expires_at`, `revoked_at NULL` |
| `external_sessions` [09] | `id`, `attempt_id`, `provider`, `external_build_id`, `external_session_id`, `status_raw`, `dashboard_url`, `fetched_at` |
| `duration_stats` [11] | PK `(suite_revision, case_key, engine)`, `sample_count`, `p50_ms`, `p90_ms`, `updated_at` |
| `target_case_assignments` [11] | `target_id`, `case_key`; PK `(target_id, case_key)` |
| `audit_events` [08 → 12] | `id`, `actor`, `action`, `object_type`, `object_id`, `occurred_at`, `metadata_redacted jsonb` |

**Required indexes** (the evidence must check them with `EXPLAIN`):
- `runs(project_id, queued_at DESC, id DESC)`
- `runs(project_id, status)`
- `run_targets(run_id)` (served by the `UNIQUE(run_id, target_key)` index, whose leading column is `run_id`)
- `run_targets(status, not_before) WHERE status = 'QUEUED'`
- `attempts(target_id, attempt_no DESC)`
- `attempts(leased_until) WHERE status IN ('CLAIMED','RUNNING')`
- `run_events(run_id, id)`
- `case_results(attempt_id)`
- `artifacts(attempt_id)`
- `artifacts(expires_at) WHERE state = 'AVAILABLE'`
- `outbox_events(id) WHERE published_at IS NULL`

**Migration rules:** Flyway versions are immutable once merged. Rows for terminal attempts, events and audit are append-only.

---

## 4. Public API (`/api/v1`)

| Method and path | Purpose | Notes | PRD |
|---|---|---|---|
| `GET /projects` | List projects | | 02 |
| `GET /projects/{projectId}/suites` | Suites with current revision | backed by the manifest | 02 |
| `GET /suites/{suiteId}` | Suite plus cases | | 02 |
| `POST /projects/{projectId}/runs` | Create a run | `202` new run, `200` replay (header `Idempotent-Replay: true`), `409` key reused with a different payload, `422` unknown suite or engine | 02 |
| `GET /runs` | List runs | filters `projectId, status, suiteKey, branch, trigger`; cursor pagination | 02 (filters 05) |
| `GET /runs/{runId}` | Run, targets, latest attempt per target | | 02 |
| `GET /runs/{runId}/results` | Every attempt with its case results, per target | | 03 |
| `POST /runs/{runId}/cancel` | Cancel | `202` pending, `200` already terminal (no change) | 04 |
| `POST /runs/{runId}/rerun` | New run with `trigger=RERUN` and `scope=FAILED_TARGETS / ALL`; `FAILED_CASES` from PRD-11 | needs an idempotency key | 05 |
| `GET /runs/{runId}/events` | Server-sent events stream (§5) | supports `Last-Event-ID` | 05 |
| `GET /workers` | Worker registry and slot usage | | 04 |
| `GET /runs/{runId}/timeline` | Ordered events grouped by target and attempt | | 06 |
| `GET /artifacts/{artifactId}` | Artifact metadata | | 06 |
| `GET /artifacts/{artifactId}/content` | Stream the bytes | `404` absent, `410` expired; `Content-Disposition: attachment`; `nosniff` | 06 |
| `POST /artifacts/{artifactId}/links` | Signed link valid for ≤ 5 minutes (HMAC + expiry) | | 06 |
| `GET /failure-groups` | Fingerprint groups | filters project, category, engine, date range | 07 |
| `GET /failure-groups/{id}` | Group detail with occurrences and evidence | | 07 |
| `PUT /failure-groups/{id}/override` | Human override of the category | `reason` required; writes an audit event | 07 |
| `GET /cases/{caseKey}/history` | Outcome and duration history | `suiteKey, engine, limit ≤ 100` | 07 |
| `GET /runs/compare?base=&head=` | Compare two runs (§8) | | 07 |
| `GET /runs/{runId}/quality` | Quality evaluation and rule report | computed when the run finishes; idempotent | 08 |
| `GET` / `PUT /projects/{projectId}/quality-policy` | Read the policy; `PUT` creates a new version | | 08 |
| `GET` / `POST /projects/{projectId}/quarantines`, `DELETE /quarantines/{id}` | Quarantine management | expiry required, at most 30 days | 08 |
| `GET /chaos/reports`, `GET /chaos/reports/{id}` | Read-only drill scoreboards and timelines | local profile with chaos enabled | 10 |
| `GET` / `PUT /scheduler` | Read or change policy and cap | local profile only; admin scope | 11 |
| `POST /tokens`, `DELETE /tokens/{id}` | Personal and CI tokens | admin scope | 12 |

**Create-run request**
```json
{
  "suiteKey": "checkout-smoke",
  "targets": [{"executor": "LOCAL", "engine": "chromium"}, {"executor": "LOCAL", "engine": "firefox"}],
  "trigger": "MANUAL",
  "idempotencyKey": "8e1258cb-1d66-4c6d-8eb8-5c28011f202e",
  "fixture": {"mode": "PRODUCT_DEFECT", "seed": 42},
  "sharding": {"mode": "NONE", "shards": 1},
  "source": {"commitSha": "0123456789abcdef", "branch": "main", "ciRef": "gh:123456/1"}
}
```

**Create-run validation (PRD-02)**
- Required: `suiteKey`, `targets` (1–3, no duplicates), `trigger`, `idempotencyKey` (1–200 of `A-Z a-z 0-9 . _ : -`). `fixture`, `sharding` and `source` are optional; `fixture` needs both `mode` and `seed` (0 … 2³¹−1).
- `source.commitSha` is 7–64 lowercase hex, `source.branch` uses git ref characters only (no `:` and no `..`), `source.ciRef` is `<system>:<id>` such as `gh:123456/1`. None of them can hold a URL.
- `400 validation-failed` until their PRDs: `executor: BROWSERSTACK` (PRD-09), `trigger: RERUN` (use the rerun endpoint, PRD-05), any sharding other than `NONE` with 1 shard (PRD-11).
- `422 fixture-not-allowed` when `TESTGRID_PROFILE` is not exactly `local`, or the mode isn't in the suite's `fixtureModes`.
- A body over 64 KB is `400 validation-failed`. JSON is parsed strictly: unknown fields, duplicate keys, strings for numbers and numbers for enums are all rejected.
- A new run gets one target per entry with `target_key = local:<engine>:0`, all `QUEUED`, plus one `RUN_CREATED` event, in one transaction.

**Idempotency**
- `request_hash` is the sha256 of the canonical JSON, with keys sorted and targets sorted, and `idempotencyKey` excluded.
- The insert relies on the unique constraint: `INSERT … ON CONFLICT DO NOTHING`, then `SELECT`.
- Same hash → replay the original run. Different hash → `409`.
- This has to hold when two identical requests arrive concurrently (test API-004).
- Normalisation before hashing (PRD-02): null fields are dropped and `sharding` defaults to `{"mode":"NONE","shards":1}`, so leaving out a default and spelling it out hash the same. Canonical JSON is the §10 form.
- Keys are unique per project. Order of checks: field validation (`400`) first, then the key. A known key is answered before the manifest checks, so a retry still replays after the suite's revision has changed.
- Under concurrency the `INSERT … ON CONFLICT DO NOTHING` waits for the other transaction holding the same key. If it commits, the waiting request reads that run and replays it or returns `409`. If it rolls back, the waiting request inserts its own run.
- Both `202` and the `200` replay carry `Location: /api/v1/runs/{runId}`. A `409` names the existing run in `detail`.

---

## 5. Events (`run_events` and SSE)

**Event envelope**
```json
{"eventId": 1042, "schemaVersion": 1, "type": "TARGET_CLAIMED", "runId": "…", "targetId": "…", "attemptId": "…", "occurredAt": "2026-11-19T12:00:00Z", "data": {"workerId": "w-1", "attemptNo": 2}}
```

**Event types**
- `RUN_CREATED`, `RUN_CANCEL_REQUESTED`, `RUN_FINISHED`
- `TARGET_DISPATCHED`, `TARGET_CLAIMED`, `TARGET_STARTED`, `TARGET_RETRY_SCHEDULED`, `TARGET_FINISHED`
- `ATTEMPT_ABANDONED`, `CASE_FINISHED`, `ARTIFACT_AVAILABLE`, `QUALITY_EVALUATED`

Clients must ignore event types they don't recognise.

**SSE wire format**
- Each event is sent as `id: <eventId>`, `event: <type>`, `data: <json>`.
- A keep-alive comment `: ping` goes out every 15 s.
- On reconnect the server replays everything after `Last-Event-ID`. If the gap is more than 1,000 events, it sends a single `RESYNC` and the client refetches the full snapshot.

---

## 6. Problem types

Each type is served as `https://testgrid.local/problems/<slug>`.

| Slug | HTTP | When |
|---|---|---|
| `validation-failed` | 400 | malformed JSON, too long, unknown field |
| `unknown-suite` / `engine-not-allowed` / `fixture-not-allowed` | 422 | not in the manifest, or a fixture mode used outside the local profile |
| `idempotency-conflict` | 409 | same key, different payload, or a different completion key after a terminal state |
| `not-found` | 404 | |
| `attempt-not-active` | 409 | late or duplicate transition on a terminal attempt |
| `lease-expired` | 410 | stale lease token |
| `stale-dispatch` | 409 | message `dispatchGeneration` older than the current one |
| `capacity-exhausted` | 429 | global session cap reached at claim time |
| `artifact-too-large` / `unsupported-media-type` | 413 / 415 | |
| `artifact-expired` | 410 | |
| `unauthorized` / `forbidden` | 401 / 403 | |
| `rate-limited` | 429 | includes a `Retry-After` header |

---

## 7. Internal worker API (`/internal/v1`)

| Endpoint | Body | Responses | PRD |
|---|---|---|---|
| `POST /claims/next` | `{workerId, executor, engines[]}` | `200` claim, `204` nothing to claim | 03 |
| `POST /targets/{targetId}/claim` | `{workerId, messageId, dispatchGeneration}` | `200` claim; `409` already claimed, terminal or stale dispatch; `429` capacity | 04 |
| `POST /attempts/{id}/start` | `{leaseToken}` | `200`; `409`; `410` | 03 |
| `POST /attempts/{id}/heartbeat` | `{leaseToken}` | `200 {leasedUntil, cancelRequested}`; `410` | 03 |
| `POST /attempts/{id}/complete` | the completion payload below | `200` (including an idempotent replay); `409`; `410`; `422` | 03 |
| `POST /attempts/{id}/artifacts` | bounded multipart upload | `201`; `413`; `415` | 06 |
| `POST /workers/register`, `POST /workers/{id}/drain` | | | 04 |
| `POST /attempts/{id}/external-session` | `{provider, buildId, sessionId}` | | 09 |

**Claim response**
```json
{"attemptId": "…", "attemptNo": 1, "leaseToken": "<opaque, 32 random bytes, base64url>", "leaseTtlSeconds": 30,
 "heartbeatIntervalSeconds": 10, "suite": {"suiteKey": "checkout-smoke", "revision": "a1b2c3d4e5f6"},
 "engine": "chromium", "caseKeys": ["cart-total", "checkout-happy-path"], "fixture": {"mode": "PASS", "seed": 42}, "timeoutMs": 120000}
```

**Completion request**
```json
{"leaseToken": "…", "completionKey": "attempt-<id>-final", "status": "COMPLETED_WITH_RESULTS",
 "caseResults": [{"caseKey": "cart-total", "status": "FAILED", "durationMs": 2912, "retryCount": 0,
   "errorClass": "ASSERTION", "errorMessage": "Expected total 59.97, received 61.97"}],
 "workerError": null}
```
- `status` is `COMPLETED_WITH_RESULTS`, `INFRA_ERROR`, `TIMED_OUT` or `CANCELLED`.
- The API checks the case keys against the claimed `caseKeys` and rejects any unknown, missing or duplicate key with `422`.
- Durations must be between 0 and the suite timeout plus a grace period.
- Lease tokens are stored only as a sha256 hash, compared in constant time, and never logged.

---

## 8. Run comparison (PRD-07)

`GET /runs/compare?base=<runId>&head=<runId>` compares two runs of the same suite key. The revisions may differ; the response notes when they do.

Each `(caseKey, engine)` pair gets one of:

| Category | Meaning |
|---|---|
| `NEW_FAILURE` | passed in base, failed in head |
| `FIXED` | failed in base, passed in head |
| `STILL_FAILING` | failed in both. `fingerprintChanged` is true if the failure signature differs |
| `STILL_PASSING` | passed in both |
| `FLAKY_IN_HEAD` | `FLAKY` status or `case_health` label `FLAKY` |
| `ADDED` / `REMOVED` | case exists in only one run |
| `NOT_COMPARABLE` | target missing or `INFRA_ERROR` on either side |

- **Duration regression:** the head duration is more than `base × 1.5` and also more than `base + 500 ms` (both thresholds configurable).
- **Shortcut:** `?base=last-green&head=<runId>` finds the most recent `PASSED` run on the same branch and suite.

---

## 9. Messaging (RabbitMQ, PRD-04)

- **Exchange:** `tg.dispatch` (direct, durable).
- **Queues:** quorum queues `tg.targets.<engine>` with routing key `engine.<engine>`.
- **Retry:** `tg.targets.retry` with a per-message TTL and a dead-letter route back to `tg.dispatch`.
- **Dead letters:** exchange `tg.dlx` → queue `tg.targets.dead`.
- **Message body:** `{messageId, targetId, dispatchGeneration, schemaVersion: 1}`, persistent, with headers `traceparent` and `x-tg-run-id`.
- **Consumers:** manual acknowledgement with `prefetch = worker slots`.
  - Ack after `complete` returns `2xx`, or after `claim` returns `409` (terminal or stale).
  - On `429` capacity, route to the retry queue.
  - After N redeliveries, dead-letter the message; the target becomes `INFRA_ERROR` and an alert metric fires.

---

## 10. Suite manifest (PRD-01/03)

The manifest lives in `worker/manifest/suites.json` and is validated by a JSON Schema in `packages/contracts`. The API seeds the `suites` and `test_cases` rows from the same file at startup.

```json
{"schemaVersion": 1, "suites": [{
  "suiteKey": "checkout-smoke", "displayName": "Checkout smoke", "specFiles": ["suites/checkout-smoke.spec.ts"],
  "allowedEngines": ["chromium", "firefox", "webkit"], "timeoutMs": 120000, "playwrightRetries": 1,
  "baseUrlRef": "DEMO_SHOP_INTERNAL", "fixtureModes": ["PASS", "PRODUCT_DEFECT", "AUTOMATION_FLAKE", "SELECTOR_DRIFT", "TIMEOUT"],
  "cases": [{"caseKey": "cart-total", "title": "cart total includes tax", "tag": "@cart-total", "critical": true}]
}]}
```

- The JSON Schema is `packages/contracts/schema/suite-manifest.schema.json` (draft 2020-12, unknown fields rejected). Beyond the schema, the API also rejects duplicate `suiteKey` or `caseKey`, a `tag` other than `@<caseKey>`, duplicate JSON keys, and spec files that don't resolve to a regular file under `worker/suites/` (symlinks included).
- `enabled` is optional (default `true`); `expectedDurationMs` per case is optional.
- `baseUrlRef` points to a worker setting that is on an allowlist; the manifest never contains a URL.
- The revision is computed from the spec files and the manifest entry, never typed by hand:
  - Input, as UTF-8: the line `testgrid-suite-revision/v1`, then the canonical JSON of the manifest entry as written (defaults not applied), then for each `specFiles` entry in manifest order its path on one line and the lowercase sha256 hex of its bytes on the next. Every line ends with `\n`.
  - Canonical JSON: object keys sorted by UTF-16 code unit (JavaScript's default sort), array order kept, no whitespace, integers only, strings escaped as `JSON.stringify` does.
  - Revision: the first 12 hex characters of the sha256 of that input. `ManifestLoaderTest.revisionFollowsTheDocumentedAlgorithm` pins it; the PRD-03 worker must reproduce it exactly.
  - Spec files are checked out with LF line endings (`.gitattributes`), so the bytes don't depend on the platform.

---

## 11. CLI contract (PRD-08)

```
testgrid run --project demo --suite checkout-smoke --engines chromium,firefox [--fixture PASS] [--wait] [--timeout 15m] [--json]
testgrid status <runId> | testgrid gate <runId> | testgrid compare --base last-green --head <runId>
```

- Exit codes are listed in §2.4.
- `--json` prints exactly one JSON document to stdout. Logs go to stderr.
- The idempotency key defaults to the sha256 of `repo + commitSha + ciRef + suite + engines`, so a re-run of the same CI job reuses the same run.

---

## 12. Quality policy schema (PRD-08)

```json
{"version": 3, "requiredTargets": ["local:chromium", "local:firefox"], "failOnCriticalCase": true,
 "maxFailedCases": 0, "failOnInfraError": true, "maxFlakyRate": 0.2,
 "durationRegression": {"enabled": true, "maxRatio": 1.5, "minDeltaMs": 500, "blocking": false},
 "quarantine": {"honor": true, "maxActive": 5, "autoMode": "SUGGEST"}}
```

- `autoMode` is `OFF`, `SUGGEST` or `AUTO`.
- `AUTO` quarantines a case only when it is labelled `FLAKY` with at least 10 observations, and the quarantine expires after 7 days. Every quarantine writes an audit event.
- Each rule in the evaluation result reports `{ruleId, observed, threshold, outcome, blocking, evidence[]}`.

---

## 13. Demo-shop fixture API (PRD-01; internal network only)

| Endpoint | Purpose |
|---|---|
| `POST /__fixture/session {mode, seed}` | Returns a session cookie. `mode` must be a `FixtureMode` value; it applies only to that browser context |
| `POST /__fixture/reset` | Restore the seeded catalogue and clear carts and orders |
| `GET /__fixture/slow?class=S\|M\|L\|XL` | Deterministic delay used by the `synthetic-duration` suite (PRD-11) |
| `GET /healthz` | Liveness |

- These routes exist only when `DEMO_SHOP_FIXTURES=enabled`; otherwise they return `404`.
- They never accept a URL, code or an arbitrary duration.

# PRD-02 — PostgreSQL domain model, REST API, migrations and idempotent run creation

**Priority:** P0 | **Estimated effort:** 14–20 h | **Dependencies:** PRD-01 | **Gate:** G1 | **Status:** not started

## Objective
Store suites, runs, targets and events durably, and expose a typed, versioned API that can't be tricked into creating duplicate runs. The API also has to survive restarts.

## User stories
- As a test engineer, I create a run for a registered suite on chosen engines and get the same answer if my client retries.
- As a frontend developer, I use generated TypeScript types and CI tells me when the API contract drifted.

## Interview signal
Relational modelling, constraints as invariants, idempotency under concurrency, keyset pagination, API design, contract-first tooling.

## Functional requirements
1. **FR-1 Migrations:** Flyway migrations `V1__core.sql` onward for `projects`, `suites`, `test_cases`, `runs`, `run_targets`, `run_events`, with every constraint and index from contracts §3 that this PRD owns.
2. **FR-2 Manifest seeding**
   - At startup the API validates `worker/manifest/suites.json` against the JSON Schema and upserts suites and cases for each revision.
   - A suite whose revision changed gets a new row. Old revisions are kept for history.
   - An invalid manifest stops startup with a clear error.
3. **FR-3 Read endpoints:** `GET /projects`, `GET /projects/{id}/suites`, `GET /suites/{id}`.
4. **FR-4 `POST /projects/{id}/runs`**
   - Bean Validation on every field.
   - Engine must be in the suite's `allowedEngines`. Fixture mode is accepted only when `TESTGRID_PROFILE=local`.
   - Creates the run, its targets (`target_key = local:<engine>:0`) and a `RUN_CREATED` event in one transaction.
5. **FR-5 Idempotency**
   - Canonical request hash (contracts §4). Concurrent duplicate requests resolve through `ON CONFLICT`.
   - `202` with `Location` for a new run, `200` plus `Idempotent-Replay: true` for a replay, `409 idempotency-conflict` for a different payload.
6. **FR-6 `GET /runs`**
   - Keyset cursor (an opaque base64url of `queued_at|id`), `limit` 1–100.
   - Stable while new runs are being inserted: no duplicates and no skipped rows across pages.
7. **FR-7 `GET /runs/{id}`:** returns the run, its targets, the aggregate status, and `links`. The aggregate is computed with the contracts §2.3 truth table, implemented as a pure function with a full unit-test table.
8. **FR-8 Errors:** a global `@ControllerAdvice` produces Problem Details (contracts §6) with `traceId`. No stack traces ever reach clients.
9. **FR-9 OpenAPI:**
   - springdoc generates `api/openapi.yaml`.
   - `openapi-typescript` generates `packages/contracts/src/api.ts`.
   - CI fails if the regenerated files differ from the committed ones (the *contract drift check*).
10. **FR-10 Ownership seam:** a fixed local project and owner, but every query is scoped by `project_id` so access control can be added later without rewriting queries.

## Tasks (in order)
- [ ] **T1** — Write Flyway migrations, constraints, indexes and manifest seeding with schema validation.
- [ ] **T2** — Implement create-run API with validation, engine allowlist and fixture-profile guard.
- [ ] **T3** — Implement idempotency with canonical hash, 202/200/409 semantics and concurrency safety.
- [ ] **T4** — Implement run list (keyset cursor) and run detail with aggregate truth-table function.
- [ ] **T5** — Add Problem Details errors, OpenAPI generation, TS client types and CI drift check.
- [ ] **T6** — Write Testcontainers repository/controller tests incl. concurrent duplicates and restart persistence.

## Acceptance criteria
- [ ] **AC-1:** POST returns `202`, and the run and targets are still readable after `docker compose restart api` (API-006).
- [ ] **AC-2:** The same key and payload return the same run ID, including 10 concurrent requests. Same key with a different payload returns `409`, and the DB holds exactly one run (API-001, API-002, API-004).
- [ ] **AC-3:** An unknown suite, a disallowed engine, a fixture mode outside the local profile, an unknown JSON field and an oversized string are each rejected with the correct problem type, and nothing is persisted (API-003).
- [ ] **AC-4:** Insert 50 runs while paging through them: no duplicates, no gaps. `EXPLAIN` shows the run list query uses `runs(project_id, queued_at DESC, id DESC)` (API-005).
- [ ] **AC-5:** The aggregate truth-table unit test covers every combination of target statuses for 1–3 targets.
- [ ] **AC-6:** The contract drift check fails CI when a DTO changes without regenerating the types (shown in the evidence by a deliberate change and its revert).

## Verification
- `./mvnw verify` (Testcontainers Postgres)
- A concurrency test using `ExecutorService` with 10 parallel POSTs
- `EXPLAIN (ANALYZE, BUFFERS)` output in the evidence
- The restart test

## Demo moment
`curl` the same POST twice: same run ID and the `Idempotent-Replay` header. Change one engine: `409`.

## Risks
- Migration drift.
- A race on duplicate idempotency keys.
- Leaking JPA entities into the API (use DTOs).

## Out of scope
Browser processes, RabbitMQ, authentication, the dashboard.

## Handoff and definition of done
- Implement **PRD-02 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T6 with tests, then write `docs/evidence/PRD-02.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain the idempotency and pagination design without AI;
  - the tracker is updated only after human review.

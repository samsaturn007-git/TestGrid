# Conflict register (PRD-00 T1 / FR-3)

**Date:** 2026-11-19. **Scope:** `MASTER_PRD.md`, `AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/API_AND_DATA_CONTRACTS.md` (vocabulary source of truth), `docs/SECURITY_AND_TEST_STRATEGY.md`, `docs/SYSTEM_TEST_MATRIX.md`, `prds/PRD-00 … PRD-15`.

**Rule:** where documents disagree, the contracts win and the other document is fixed in the same PR (ARCHITECTURE preamble, AGENTS rule 5).

**Result: 0 open contradictions.** Re-run `bash tools/vocab_check.sh` whenever a doc or PRD changes.

## 1. Vocabulary sign-off (contracts §2)

- All 12 enums in contracts §2.1 (`RunStatus`, `TargetStatus`, `AttemptStatus`, `CaseStatus`, `QualityOutcome`, `ErrorClass`, `FailureCategory`, `FixtureMode`, `Trigger`, `Executor`, `Engine`, `ShardingMode`; 52 distinct values) are confirmed as canonical.
- The target transition table (§2.2), the target-status-from-case-results rule, the run aggregate truth table (§2.3: not-all-terminal → `RUNNING` if ever claimed else `QUEUED`; all terminal → first of `FAILED > TIMED_OUT > INFRA_ERROR > CANCELLED > PASSED`) and the quality outcome / exit-code table (§2.4: 0 / 1 / 2 / 3) are confirmed.
- Automated check (`tools/vocab_check.sh`) verifies: every declared enum value is used with the same spelling; no known misspelling (`CANCELED`, `TIMEDOUT`, `SUCCEEDED`, `IN_PROGRESS`, `PENDING`, …) appears; every `<HTTP code> <slug>` problem reference exists in contracts §6; every event type exists in contracts §5. Output is recorded in `docs/evidence/PRD-00.md`.

## 2. Conflicts resolved before PRD-00 (inherited from the v2 kit)

All 14 conflicts listed in `CHANGES_v2.md` §4 were re-checked against the current files today. Each resolution is present in the documents named.

| # | Conflict | Resolution | Confirmed in | Status |
|---|---|---|---|---|
| C1 | Polling worker had no claim endpoint | `POST /internal/v1/claims/next` (PRD-03); claim by target ID from PRD-04 | contracts §7, ARCHITECTURE §2 | Closed |
| C2 | Fixture mode lists differed | `PASS, PRODUCT_DEFECT, AUTOMATION_FLAKE, SELECTOR_DRIFT, TIMEOUT` + worker-injected `BROWSER_LAUNCH` = 6 scenarios | contracts §2.1, SECURITY fixture table, PRD-01 FR-6 | Closed |
| C3 | Status spelling `PASS/FAIL/TIMEOUT` vs `PASSED/FAILED/TIMED_OUT` | Canonical enums in contracts §2; `PASS`/`FAIL` only as `QualityOutcome`/`FixtureMode` | contracts §2.1, AGENTS engineering standards; grep in §1 | Closed |
| C4 | Auth needed in PRD-08 but arrived in PRD-12 | Service token PRD-03, CI token PRD-08, scoped user tokens PRD-12 | contracts §3 `api_tokens`, PRD-03/08/12 | Closed |
| C5 | No durable event log for SSE replay | `run_events` table (PRD-02) | contracts §3, §5 | Closed |
| C6 | Scheduler had nowhere to choose order | DB dispatcher owns order and cap; broker is transport | ARCHITECTURE §3, **ADR-004** | Closed |
| C7 | Attempt states undefined | `AttemptStatus` incl. `ABANDONED` | contracts §2.1, §2.2 | Closed |
| C8 | Cancel endpoint had no owner | PRD-04 FR-8 | contracts §4 (`POST /runs/{runId}/cancel`, PRD 04) | Closed |
| C9 | `ASSERTION` vs `PRODUCT_SUSPECTED` overlapped | `ErrorClass` (raw) separated from `FailureCategory` (interpreted) | contracts §2.1, SECURITY fixture table | Closed |
| C10 | Stopped-worker exit code "2/3" | 2 = indeterminate, 3 = config/auth only | contracts §2.4, MASTER §3, PRD-08 AC-3, CI-003/004 | Closed |
| C11 | Statistics wording (variance / IQR / p95) | Median, IQR and p95 everywhere | MASTER §3, PRD-11 | Closed |
| C12 | Quality policy lacked required targets / critical cases | Policy schema in contracts §12 | contracts §12 | Closed |
| C13 | PRD-03 said "clicking" before the UI existed | API submit in PRD-03; UI in PRD-05 | PRD-03, PRD-05 | Closed |
| C14 | PRD-09 titled "Optional" but tracked as core | Abstraction and mock required; live run optional | PRD-09 title, MASTER §7 note, progress.json `optional: false` | Closed |

## 3. Items found during today's review

None of these is a contradiction; each is recorded so nobody "fixes" it the wrong way later.

| # | Observation | Where | Ruling | Status |
|---|---|---|---|---|
| N1 | PRDs write path parameters as `{id}` (e.g. `GET /runs/{id}`), contracts use `{runId}`, `{artifactId}`, `{projectId}`. | PRD-02, 05, 06, 07, 08 vs contracts §4 | Same endpoints, same semantics. Implementation and OpenAPI use the contracts' names. | Closed (cosmetic) |
| N2 | `TIMEOUT` is both a `FixtureMode` value and a `FailureCategory` value; the matching status is `TIMED_OUT` and the error class `TEST_TIMEOUT`. | contracts §2.1, SECURITY fixture table, PRD-07 rule R2 | Intentional: different enums. Code must use typed enums so they can't be confused. | Closed (by design) |
| N3 | `FAILING` appears as a word. | contracts §3 (`case_health.label`), PRD-07 | It is only the `case_health` label (`STABLE / FLAKY / FAILING / INSUFFICIENT_DATA`), never a run/target/case status. `vocab_check.sh` enforces this. | Closed |
| N4 | ARCHITECTURE §9 lists `worker` in the `default` Compose profile; PRD-01 FR-8 Compose has only `postgres`, `api`, `web`, `demo-shop`. | ARCHITECTURE §9 vs PRD-01 | Staged delivery, not a conflict: the worker service joins the default profile in PRD-03. | Closed (staging) |
| N5 | AGENTS standard says Java 21; this machine's default `java` is 26.0.1. | AGENTS vs local env | Environment gap, not a doc conflict. Repo pins Java 21 (`docs/VERSIONS.md`); PRD-01 selects JDK 21 per repo. | Closed (env action tracked in VERSIONS §2) |
| N6 | `make smoke` in PRD-01 runs the spec "directly against demo-shop" while the manifest arrives in PRD-03. | PRD-01 FR-7, contracts §10 | Consistent: PRD-01 FR-7 says the spec moves into the manifest in PRD-03. | Closed |

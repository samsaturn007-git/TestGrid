# TestGrid kit — v2 changes

**Date:** 2026-11-19 | **Basis:** v1 package (36 files, all hashes verified) plus a full consistency review.

## 1. What v2 is
- **The same 16 PRDs and the same order.** No new PRDs.
- **Every PRD is far more detailed:** user stories, interview signal, numbered functional requirements (FR-n), concrete endpoints, tables and UI pages, ACs linked to test IDs, verification commands, and a "demo moment".
- **Four showcase features are folded into existing PRDs** (the "Deepen + targeted adds" scope you chose).
- **Effort:** core PRDs 00–13 grow from about 150–235 h to about **178–270 h**. Core tasks grow from 86 to 93.

## 2. Showcase additions
| Addition | Where | What it adds |
|---|---|---|
| Observability stack | PRD-03 (correlation IDs), PRD-04 (`traceparent` over AMQP), **PRD-12** (full stack) | OpenTelemetry traces from API → outbox → AMQP → worker → Playwright, Prometheus metrics, a provisioned Grafana dashboard, Jaeger, alert rules |
| Run comparison and history | **PRD-07** | `GET /runs/compare` (new failure / fixed / still failing / flaky / duration regression), a "compare with last green" shortcut, case history with sparklines |
| Flaky quarantine | **PRD-08** | Quarantine with required reason, expiry ≤ 30 days, cap and audit; `SUGGEST` / `AUTO` modes; `QUARANTINED_FAIL` always reported |
| Smart sharding | **PRD-11** | Duration-balanced LPT bin-packing vs Playwright native `--shard`; case assignments; rerun of failed cases; a second benchmark experiment |

## 3. Other upgrades worth showing off
- **PRD-01:** per-session fixture modes, so concurrent runs can use different failure modes. New `SELECTOR_DRIFT` mode (automation broken, product fine). A fake local payment rule.
- **PRD-02:** concurrent-safe idempotency (canonical hash, `ON CONFLICT`), keyset pagination with an `EXPLAIN` check, an aggregate truth table, an OpenAPI → TypeScript **contract drift check** in CI.
- **PRD-03:** `FOR UPDATE SKIP LOCKED`, hashed lease tokens, DB-clock leases, a partial unique index enforcing one active attempt, process-group cleanup, a second suite.
- **PRD-04:** a DB dispatcher (ADR-004), dispatch generations as fencing tokens, a retry queue with TTL, cancellation races, worker draining.
- **PRD-05:** SSE with `Last-Event-ID` replay plus `LISTEN/NOTIFY`, a polling fallback, a workers page, rerun of failed targets, Playwright tests of the dashboard itself.
- **PRD-06:** magic-byte MIME sniffing, HMAC signed links, an orphan sweeper, a canary-secret scan, an attempt timeline with heartbeat gaps.
- **PRD-09:** an executor interface, capability-profile allowlist, budget guard, HTTP mock contract tests.
- **PRD-10:** 10 named drills including the zombie worker (`SIGSTOP`/`SIGCONT`), an I1–I9 invariant validator, recovery-time metrics.
- **PRD-12:** scoped tokens, container hardening, gitleaks, Trivy and CodeQL, k6 and a NoopExecutor orchestration baseline, a static read-only demo.

## 4. Conflicts resolved (input to the PRD-00 conflict register)
| # | v1 conflict | v2 resolution |
|---|---|---|
| 1 | The polling worker had no claim endpoint | `POST /internal/v1/claims/next` (PRD-03); claim by target ID from PRD-04 |
| 2 | Fixture modes differed (PRD-01 vs security doc) | One list: `PASS, PRODUCT_DEFECT, AUTOMATION_FLAKE, SELECTOR_DRIFT, TIMEOUT`, plus a worker-injected `BROWSER_LAUNCH`, giving 6 labelled scenarios |
| 3 | Status spelling (`PASS`/`FAIL`/`TIMEOUT` vs `PASSED`/`FAILED`/`TIMED_OUT`) | Canonical enums in contracts §2; all PRDs aligned; AGENTS forbids new spellings |
| 4 | Auth needed in PRD-08 but only arrived in PRD-12 | Service token in PRD-03, CI token in PRD-08, full scoped tokens in PRD-12 |
| 5 | No durable event log for SSE replay | `run_events` table (PRD-02) |
| 6 | Scheduler had nowhere to choose the order | The DB dispatcher owns order and cap; the broker is transport (ARCHITECTURE §3, ADR-004) |
| 7 | Attempt states undefined | `AttemptStatus` enum including `ABANDONED` |
| 8 | Cancel endpoint had no owner | PRD-04 FR-8 |
| 9 | Taxonomy `ASSERTION` vs `PRODUCT_SUSPECTED` overlapped | `ErrorClass` (raw) separated from `FailureCategory` (interpreted) |
| 10 | Exit code for a stopped worker was "2/3" | 2 = indeterminate, 3 = config or auth only |
| 11 | Statistics wording differed (variance / IQR / p95) | Median, IQR and p95 everywhere |
| 12 | Quality policy lacked required targets and critical cases | Policy schema in contracts §12 |
| 13 | PRD-03 said "clicking" before the UI existed | API submit in PRD-03; UI in PRD-05 |
| 14 | PRD-09 titled "Optional" but tracked as core | Clarified: the abstraction and mock are required, the live run is optional |

## 5. Tooling fixes
- **`tools/progress.py`**
  - `unblock` no longer allows two PRDs in progress at once.
  - `block` works only on an in-progress PRD.
  - `done` now rejects template placeholders and evidence that doesn't mention every AC in the PRD, and it requires `**Outcome:** pass`.
  - New `lint` command checks that the tracker's tasks match the PRD files.
  - The status output lists optional eligible PRDs too.
- **`docs/evidence/TEMPLATE.md`:** test-ID column, tasks and commits, changed files, threat check, skipped or mocked tests, and the "three most important decisions" explain-back.
- **Generated from the PRDs:** `progress.json`, `BACKLOG.csv`, `docs/PRD_INDEX.md`, `PROGRESS.md`. `MANIFEST.sha256` is regenerated.

## 6. Deliberately unchanged
- PRD-00 still asks Codex to *write* the ADRs. v2 only proposes ADR-004's direction, so you still make and explain the decisions yourself.
- No versions were pinned in this package. PRD-00 T5 verifies them against official sources on the day.
- BrowserStack endpoints and SDK options are not hard-coded. PRD-09 T1 researches them first.

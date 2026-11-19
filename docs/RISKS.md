# Risk register — top 8

**Created:** 2026-11-19 (PRD-00 T3). **Review:** at every PRD close; update likelihood/impact from evidence, never from optimism.

Scale — **Likelihood:** Low / Medium / High. **Impact:** Low / Medium / High / Critical.

| # | Risk | Likelihood | Impact | Mitigation | Verified in | Test ID(s) |
|---|---|---|---|---|---|---|
| R1 | **Scope creep / burnout.** AI suggestions or the four v2 showcase features widen scope beyond what a part-time solo developer can finish alongside DSA prep. | High | High | One active PRD enforced by `tools/progress.py`; PRD branches; out-of-scope sections; PRD-10+ deferrable ("portfolio-ready at PRD-09"); UI extras marked "if it doesn't delay evidence". | Every PRD close (evidence "deferred" section); PRD-09 portfolio checkpoint | REL-001 (end-to-end proof the delivered scope actually works) |
| R2 | **Duplicate or late worker callbacks** (broker redelivery, zombie worker after `SIGSTOP`/`SIGCONT`) create contradictory terminal records. | High | Critical | Partial unique index on active attempts; conditional `UPDATE … WHERE status IN (non-terminal)`; hashed lease tokens + DB-clock `leased_until`; completion keys; dispatch generations as fencing tokens; `410 lease-expired`. | PRD-03, PRD-04, PRD-10 | Q-002, Q-005, Q-008, CHAOS-002, CHAOS-003 |
| R3 | **Worker crash leaves a target permanently active** (stuck runs). | Medium | High | Heartbeats + reaper every 5 s; `ABANDONED` attempts; bounded retries with backoff/jitter; terminal `INFRA_ERROR` after `max_attempts`; DLQ; `stuck_runs` operator query (I8). | PRD-04, PRD-10 | Q-004, Q-009, CHAOS-001 |
| R4 | **Global session cap exceeded** under concurrent dispatch/claim, overloading a 16 GB laptop and invalidating benchmarks. | Medium | High | DB dispatcher under `pg_advisory_xact_lock` owns the cap (ADR-004); claim-time capacity re-check (`429`); per-service memory limits in `docs/VERSIONS.md`. | PRD-04, PRD-11 | Q-001, SCHED-001 |
| R5 | **Hostile input → RCE or SSRF** (uploaded specs, client URLs, grep/flag injection). | Low (by design) | Critical | ADR-003 manifest-only suites; no URL/code/regex/flag fields; unknown JSON fields rejected; typed argv builder with `shell: false`; `baseUrlRef` allowlist enforced in API and worker. | PRD-02, PRD-03, PRD-12 | API-003, SEC-001 |
| R6 | **Secret leakage** (BrowserStack keys, service/CI tokens) into logs, traces, screenshots, artifacts or the repo. | Medium | Critical | Credentials from environment only; `.env.example` fake values; redactor in API and worker; span-attribute redaction; canary-secret scan; gitleaks in CI. | PRD-03, PRD-06, PRD-09, PRD-12 | SEC-002, SEC-003, CLOUD-002 |
| R7 | **Quarantine or failure classification hides real product bugs** (infra faults labelled as product defects, or vice versa; quarantine used as a gate bypass). | Medium | High | Rule-table classifier scored against 6 labelled scenarios with a published confusion matrix; infra never `PRODUCT_SUSPECTED`; quarantine needs reason, expiry ≤ 30 days, `maxActive` cap, audit; `QUARANTINED_FAIL` always reported. | PRD-07, PRD-08 | INT-002, QUA-001, QUA-002, CI-003 |
| R8 | **Unsupported or fabricated metrics** (benchmark or résumé numbers not backed by data; versions pinned from memory). | Medium | High | Raw CSVs + scripts committed; ≥ 5 paired trials per cell; median/IQR/p95; "where it didn't help" section; synthetic data labelled; versions verified against official sources (`docs/VERSIONS.md`). | PRD-00 (versions), PRD-11, PRD-13 | SCHED-001, SHD-002, REL-001 |

## Watch list (below the top 8)

- **Browser/OS differences:** local WebKit ≠ Safari on Apple hardware (MASTER non-goal); pinned Playwright image; limits documented in PRD-03 T7 (E2E-002).
- **Docker resource limits on the dev machine:** no engine currently running (see `docs/VERSIONS.md` §2) — blocks PRD-01 T5 until installed.
- **BrowserStack cost/limits:** cloud executor is opt-in with a budget guard; local always works (CLOUD-001).
- **AI-generated code not understood:** explain-back quiz at every PRD close; hard parts written by hand.

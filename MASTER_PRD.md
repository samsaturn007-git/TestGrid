# MASTER PRODUCT REQUIREMENTS DOCUMENT — TestGrid

**Version:** 2.0 | **Date:** 2026-11-19 | **Owner:** Solo engineer | **Status:** Proposed planning baseline, not implemented

## 1. Executive summary

TestGrid is a local-first developer platform. It runs pre-registered Playwright end-to-end suites against an owned demo application on Chromium, Firefox and WebKit, and optionally on BrowserStack Automate.

What it does:
- dispatches work durably to a capped pool of workers;
- streams live status;
- captures traces, screenshots and logs;
- groups failures with explainable fingerprints;
- detects and quarantines flaky tests;
- compares runs across commits;
- gates CI with exit codes that separate test failures from infrastructure failures;
- traces every run end to end with OpenTelemetry.

**Its differentiators are proven reliability and measured scheduling, not AI buzzwords:**
1. **Verifiable failure recovery.** A chaos lab with automated invariant checks.
2. **Duration-aware scheduling and smart sharding.** Benchmarked against FIFO and Playwright's native sharding, including the cases where they don't help.
3. **Explainable test health.** Fingerprints, flaky detection, quarantine and run comparison, scored against labelled ground truth.
4. **Full observability.** One trace from the dashboard click to each browser case.

**Portfolio objective:** a graduate SDE I interviewer inspecting the repository sees clean code, data modelling, API design, browser automation, concurrency, idempotency, distributed-systems failure handling, observability, security awareness, CI and measured performance, with every claim backed by a committed test or dataset.

**Primary developer persona:** an engineer with internships and React/Node/Spring projects, exposure to Playwright and Selenium, and beginner-level interview DSA. The project must strengthen engineering foundations while staying explainable without AI.

## 2. Problem, user, and value

When a test fails, developers struggle to tell whether the product, the automation, the browser environment or the worker infrastructure is at fault. Local runs lack durable history, comparison across commits, flaky-test management and CI gates that tell "red" apart from "unknown". TestGrid puts these capabilities into one understandable, reproducible platform.

- **Primary user:** a frontend or full-stack engineer maintaining a sample shop.
- **Secondary user:** a hiring engineer evaluating architecture and evidence.
- **Non-user:** an anonymous stranger uploading untrusted code.

## 3. Measurable outcomes (targets, NOT achievements)

| Goal | Validation target / method |
|---|---|
| First usable run | From a clean checkout, run a registered suite on one browser and see stored results; steps documented |
| Cross-browser | Same manifest on Chromium, Firefox and WebKit; platform limits noted |
| Durability | A worker crash never leaves a target permanently active; retry or terminal state within the lease and retry bounds (I8) |
| Duplicate handling | Repeated deliveries and completions never create contradictory terminal records (I1, I2) |
| Observability | Every attempt has timestamps, status, structured failure info and artifacts; one trace ID spans API → queue → worker → cases |
| Failure intelligence | Confusion matrix on 6 labelled scenarios; infra faults never labelled product defects |
| Run comparison | Base vs head categorisation is correct on seeded fixtures (CMP-001/002) |
| CI gate | PASS → exit 0, product failure → 1, infra or indeterminate → 2, config → 3; quarantine audited and expiring |
| Scheduler and sharding | At least 5 matched trials per cell; median, IQR and p95 makespan reported, including cases with no advantage |
| Chaos | Drills D1–D10 at ≥10 repeats each, with invariant results and recovery times |
| Safety | Only manifest suites and allowlisted origins can run; secrets redacted; artifacts access-controlled; scans clean |
| Portfolio evidence | One-command demo, reproducible scripts, CI runs, benchmark data, chaos scoreboard, 3–4 minute video |

## 4. In-scope capabilities

1. A monorepo with a pnpm workspace, Maven wrapper and Docker Compose profiles.
2. An owned demo shop with per-session fixture modes: `PASS, PRODUCT_DEFECT, AUTOMATION_FLAKE, SELECTOR_DRIFT, TIMEOUT`.
3. Durable suites, runs, targets, attempts, case results, events and artifacts, with an idempotent API and keyset pagination.
4. One polling worker first, then RabbitMQ with a transactional outbox, a DB dispatcher and at-least-once delivery.
5. A global session cap, leases with fencing generations, a heartbeat and reaper, bounded retries, cancellation and draining workers.
6. A React dashboard: browser matrix, live SSE with replay, worker registry, rerun of failed targets, accessible UX.
7. Screenshots, traces and redacted logs; secure, expiring artifact retrieval; an attempt timeline.
8. Deterministic failure fingerprints, a rule-based taxonomy with override, rolling flaky detection, case history and **run comparison**.
9. A CLI and GitHub Actions quality gate with versioned policies, **flaky quarantine** and a deployment veto.
10. An executor abstraction and an optional BrowserStack Automate adapter, mock-tested.
11. A chaos lab: 10 drills, an invariant validator, a scoreboard.
12. **Duration-aware LPT scheduling with aging, smart duration-balanced sharding**, rerun of failed cases, transparent benchmarks.
13. Security hardening, scoped tokens, scans, an **OpenTelemetry + Prometheus + Grafana + Jaeger** stack, a k6 load baseline, and a static read-only demo.
14. A reviewer-first README, a demo video, an evidence index, a truthful resume kit.

## 5. Explicit non-goals

- Recreating BrowserStack's commercial grid, device cloud or real-device infrastructure.
- Arbitrary code execution or arbitrary URLs from anonymous users.
- Claiming that local Playwright WebKit is the same as Safari on Apple hardware.
- Kubernetes, billing, organisations, SSO, generative AI agents or ML schedulers in the core scope.
- A production multi-tenant SaaS, or a promise of zero lost jobs under every failure.
- Copying proprietary code, UI assets or branding.

## 6. Major decisions (to be ratified as ADRs in PRD-00)

- **Frontend:** React, TypeScript, Vite, Tailwind, TanStack Query, React Router; generated API types.
- **API:** Java 21, Spring Boot, Spring Security, Flyway, springdoc-openapi; REST plus SSE; Problem Details errors.
- **Worker and CLI:** Node, TypeScript, Playwright Test, zod, pino; runs only manifest-registered suites.
- **Persistence:** PostgreSQL is the canonical record; DB constraints enforce invariants; transactional outbox.
- **Queue:** RabbitMQ from PRD-04: quorum queues, manual ack, a retry queue with TTL, a DLQ.
- **Scheduling:** the DB dispatcher owns ordering and the cap; the broker is transport only (ADR-004).
- **Artifacts:** a local volume, with an optional S3-compatible adapter; never blobs in SQL.
- **Observability:** OpenTelemetry (Java agent and Node SDK) → Collector → Jaeger and Prometheus → Grafana.
- **Local environment:** Docker Compose profiles `default` (RabbitMQ included from PRD-04, CONFLICT_REGISTER N14), `obs`, `s3`, `chaos`; `.env.example` with fake values.
- **Contracts:** `docs/API_AND_DATA_CONTRACTS.md` is the vocabulary source of truth; a CI drift check on the generated types.
- **Cloud:** official BrowserStack SDK and REST API, with credentials from the environment only.

## 7. Release and progress gates

| Gate | PRDs | User-visible result | Required proof |
|---|---|---|---|
| G0 — Design | 00 | Accepted architecture, ADRs, risks, versions | ADRs, conflict register, risk register |
| G1 — First run | 01–03 | Run a known suite and retrieve per-case results | API and E2E tests, CI green, log of a real run |
| G2 — Durable platform | 04–06 | Parallel capped workers, live UI, artifacts and timeline | Integration tests, worker-crash evidence |
| G3 — Portfolio MVP | 07–09 | Failure intelligence, comparison, gate and quarantine, cloud adapter | Confusion matrix, CI gate demo, mock contract tests (live cloud run optional) |
| G4 — Engineering differentiation | 10–12 | Chaos lab, scheduler and sharding benchmarks, security and observability | Drill scoreboard, benchmark CSVs and plots, traces, scans |
| G5 — Presentation | 13 | Impressive, truthful public portfolio | README, demo video, evidence index, release tag |
| Optional | 14–15 | Visual regression, AI-assisted explanation | Each has its own gates |

**Note:** a fully local variant with no cloud account passes G3 with a mock-tested adapter and a documented live procedure. Never claim a live cloud integration that didn't happen.

## 8. Delivery boundaries

- Each numbered PRD is a separately reviewable unit and must not spill into the next PRD without an approved change record.
- Exactly **one** PRD is active at a time. When stuck, record the blocker in that PRD's evidence file and narrow the work to a smaller vertical slice.
- PRD-10 onward can be delayed if interview timing requires focusing on DSA. **Portfolio-ready at PRD-09.**
- Codex summarises the files it changed, the tests it ran, the trade-offs, the threats and anything unfinished at every PRD close.

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| The extra v2 features overwhelm a solo developer | Additions are folded into existing PRDs with their own tasks; PRD-10+ can be deferred; UI extras marked "if it doesn't delay evidence" |
| Browser or OS differences | Pinned Playwright image and versions; documented platform limits |
| Duplicate deliveries and zombie workers | Generations, lease tokens, idempotent completion, partial unique index, chaos drills |
| Hard-to-debug stuck runs | Reaper, immutable attempts, timeline, end-to-end traces, `stuck_runs` query |
| Hostile inputs | Manifest allowlist, local-first, least-privilege containers |
| Quarantine hiding real bugs | Expiry, cap, scopes, audit, always-visible `QUARANTINED_FAIL` |
| BrowserStack cost or limits | Local mode always works; budget guard; cloud is opt-in |
| AI-generated code not understood | Explain-back in every PRD, short commits, ADRs, hard parts written by hand |
| Unsupported metrics | Raw CSVs, published scripts, honest statistics, a "where it didn't help" section |

## 10. Effort

Core PRDs 00–13 total roughly **180–270 hours**, up from 150–235 in v1 because of the four additions and the extra detail. At 10–14 hours a week that is about 4–6 months. These are planning ranges, not promises.

## 11. Definition of project completion

PRD-00 through PRD-13 all have their tasks checked, reviewed evidence files, deterministic CI runs and no unresolved critical security issues. A reviewer can go from a clean checkout to a reproduced failure, a verified recovery and a traced run. PRD-14 and PRD-15 stay optional.

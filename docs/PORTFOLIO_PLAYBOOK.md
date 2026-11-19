# Portfolio, benchmarking and interview presentation playbook — v2

## Public GitHub README outline

1. **Title and one-line benefit:** "Reliable, observable cross-browser test orchestration with failure-aware analytics."
2. **30-second GIF:** submit a suite → three browser targets fill in live → failure detail with trace → compare with last green.
3. **What this demonstrates:** REST, an idempotent API, a transactional outbox, leases, SSE, Playwright, artifact security, rule-based analytics, a CI gate, OpenTelemetry, chaos testing, scheduling algorithms.
4. **Quick start:** `make up && make demo`, with pinned versions and no paid accounts.
5. **Architecture:** diagram plus links to ADR-001 to ADR-004 (why a queue, why an outbox, why leases and generations, why the DB dispatcher).
6. **Correctness proof:** invariants I1–I9, the chaos scoreboard, duplicate-message and zombie-worker drills.
7. **Benchmarks:** method, raw CSVs, box plots, and "where it didn't help". No cherry-picking.
8. **Test health:** a fingerprint confusion matrix, a flaky-detection example, a comparison screenshot.
9. **Observability:** a Jaeger trace waterfall and Grafana dashboard screenshots.
10. **Security model:** manifest-only execution, no client URLs or code, scoped tokens, scans, audited quarantine.
11. **Limitations:** Playwright engines vs real devices, an account-dependent cloud adapter, a single node, no multi-tenancy.

## 3–4 minute demonstration script

| Time | Show |
|---|---|
| 0:00–0:20 | The problem and architecture; create a run on three engines from the dashboard |
| 0:20–0:50 | Live matrix filling in; the worker registry; an attempt timeline |
| 0:50–1:25 | `PRODUCT_DEFECT`: failed cell → screenshot → trace → fingerprint group → "compare with last green" |
| 1:25–2:05 | Chaos D7: freeze a worker, the reaper reassigns its target, the late callback is rejected `410`; scoreboard |
| 2:05–2:35 | Open the run's trace in Jaeger (API → AMQP → worker → cases); Grafana sessions vs cap |
| 2:35–3:05 | PR check fails with a summary table; quarantine the flaky case → passes with a visible note; deploy job vetoed on red |
| 3:05–3:40 | Benchmark box plots: FIFO vs LPT and native vs duration-balanced shards, with real numbers and the tie case |

## Benchmark protocol (run against real code, never fabricated)

- **Fix everything except the variable under test:** suite revision, browser image, hardware and OS, worker count, workload seed, and the warm-up.
- **Experiment A (ordering):** FIFO vs LPT_AGING at caps 1, 2 and 3.
- **Experiment B (sharding):** NONE vs PLAYWRIGHT_NATIVE vs DURATION_BALANCED at 2 and 3 shards.
- **Trials:** at least 5 matched trials per cell, alternating which strategy runs first.
- **Collect:** makespan, median and p95 queue wait, utilisation, failures, standard deviation and IQR.
- **Archive:** `benchmarks/*.csv`, the scripts and the plots.
- **Keep data sets separate:** `synthetic` vs `observed`, cold vs warm browsers.

**Expected caveats.** LPT tends to help when durations vary a lot and parallelism is limited. Equal durations, scheduling overhead, cache effects and browser start-up can cancel the benefit. Never claim a speedup before the data shows it.

## Resume bullets — templates, use only when TRUE

**TestGrid — Cross-Browser Test Orchestration Platform** | Java, Spring Boot, TypeScript, React, PostgreSQL, RabbitMQ, Playwright, OpenTelemetry, Docker, GitHub Actions

- Built a test orchestration service with transactional-outbox dispatch, lease-and-generation fencing and idempotent result finalization. Verified with a 10-drill chaos lab (worker kill, duplicate delivery, zombie worker, broker restart) over **[N measured]** repetitions with zero invariant violations **[only if true]**.
- Implemented explainable failure intelligence: normalized fingerprints, a rule-based taxonomy, rolling flaky detection with audited quarantine, and run-to-run comparison, evaluated against a labelled fixture set **[insert measured precision/recall or omit]**.
- Designed a duration-aware LPT scheduler with aging and duration-balanced sharding, benchmarked against FIFO and Playwright native sharding over ≥5 matched trials per configuration **[insert measured median change or "no significant difference"]**.
- Instrumented API, queue and workers with OpenTelemetry, Prometheus and Grafana. Shipped a CI quality gate with exit codes that separate product failures from infrastructure errors.

No numbers until they're measured. If the BrowserStack path was only mocked, say so. Don't list AI unless PRD-15 is implemented and evaluated.

## Interview defence questions

1. What exactly are the failure guarantees? (At-least-once delivery with idempotent DB state, vs exactly-once external execution.)
2. Why a transactional outbox? Where can messages be lost or duplicated?
3. What happens when a worker completes and then crashes before acknowledging?
4. How do the lease token and dispatch generation stop a zombie worker from overwriting a newer result?
5. Why `FOR UPDATE SKIP LOCKED`? What would go wrong with a plain `SELECT` and then `UPDATE`?
6. Why does the DB dispatcher own the ordering instead of RabbitMQ? What does that cost?
7. How do SSE reconnects avoid showing a stale UI? Why both `LISTEN/NOTIFY` and a table?
8. Why separate `TIMED_OUT`, `INFRA_ERROR` and product assertion failures, all the way to exit codes?
9. How is fingerprint normalisation designed? What's a collision, and how would you notice one?
10. What stops quarantine from hiding real bugs?
11. LPT's complexity and its known worst-case makespan bound. When does it lose to FIFO? How does aging bound starvation?
12. How does duration-balanced sharding work, and why is Playwright's native sharding the baseline?
13. Follow one trace through the system. How does context cross the AMQP boundary?
14. What happens if PostgreSQL, the broker or the artifact store is down?
15. Which local WebKit vs real Safari differences matter?
16. What would you change for a real multi-tenant SaaS?

## DSA learning opportunities in actual code

- Finite-state machines and queues: the lifecycle of targets and attempts.
- Hash maps and sets: idempotency, deduplication, fingerprint grouping, O(n) run comparison.
- Heaps: LPT selection (max-heap) and shard balancing (min-heap).
- Greedy algorithms and approximation: LPT makespan and bin-packing.
- Sliding windows and rolling aggregates: flip rate, p50 and p90 durations.
- Keyset pagination and index-backed queries: thinking about complexity in SQL.

For each algorithm, write a small isolated unit test and a short note on its time and space complexity.

# End-to-end acceptance matrix (TestGrid v2)

**Rule:** these are planned tests, not passing results. Each PRD evidence file links the executed test and its output by test ID. Invariants I1–I9 refer to ARCHITECTURE §5.

## Execution and API
| Test ID | Scenario | Expected result | PRD |
|---|---|---|---|
| E2E-001 | Run `checkout-smoke` on Chromium | Run → target → attempt → 6 case results stored, `PASSED` | 03 |
| E2E-002 | Run on Chromium, Firefox and WebKit | One result per target; engine-specific limitations documented | 03/04 |
| API-001 | Same idempotency key twice, same payload | Same run ID, `200` + `Idempotent-Replay`, one run in the DB | 02 |
| API-002 | Same key, different body | `409 idempotency-conflict`, no duplicate run | 02 |
| API-003 | Unknown suite, disallowed engine, fixture outside local, unknown field, URL-like string | `4xx` with the correct problem type; nothing queued | 02 |
| API-004 | 10 concurrent POSTs with the same key | Exactly one run; every response carries the same run ID | 02 |
| API-005 | Paging while inserting 50 runs | No duplicates or gaps; `EXPLAIN` uses the keyset index | 02 |
| API-006 | Restart the API after creating a run | Run and targets readable and unchanged | 02 |

## Queue, leases and recovery
| Test ID | Scenario | Expected result | PRD |
|---|---|---|---|
| Q-001 | 20 targets with cap 3 | Sampled active sessions never exceed 3 (I5) | 04 |
| Q-002 | Same broker message delivered 3 times | One valid active lease, one terminal result (I1, I2) | 04 |
| Q-003 | Crash after publish, before outbox mark | Re-publish is harmless; final state consistent | 04 |
| Q-004 | Worker killed after claim | Old attempt `ABANDONED`; retry or bounded `INFRA_ERROR` (I3, I8) | 04/10 |
| Q-005 | Late callback from an expired or replaced attempt | `410`/`409`; the current attempt stays authoritative | 03/04/10 |
| Q-006 | Broker restart mid-run | No committed target lost (I7); run terminal | 04 |
| Q-007 | Cancel races with completion | Exactly one terminal target state; both requests audited | 04 |
| Q-008 | Crash after complete, before AMQP ack | Redelivery acked as terminal; no second attempt | 04 |
| Q-009 | Retries exhausted | Target `INFRA_ERROR`, DLQ depth > 0, metric increments | 04 |

## UI
| Test ID | Scenario | Expected result | PRD |
|---|---|---|---|
| UI-001 | SSE connection blocked, then restored | UI resyncs to DB state; no stale green status | 05 |
| UI-002 | Inject `PRODUCT_DEFECT` | Failure visible with correct engine, attempt and case | 05/06 |
| UI-003 | Rerun failed targets | Linked run containing only the failed targets | 05 |

## Artifacts
| Test ID | Scenario | Expected result | PRD |
|---|---|---|---|
| ART-001 | Oversized or wrong-MIME upload | `413`/`415`; no file created | 06 |
| ART-002 | Download an expired artifact | `410`; the UI explains the expiry | 06 |
| ART-003 | `../` traversal, symlink, forged ID, unsigned link | Denied; no escape from the storage root | 06/12 |

## Failure intelligence and comparison
| Test ID | Scenario | Expected result | PRD |
|---|---|---|---|
| INT-001 | Same seeded assertion failure repeated 5 times | One stable fingerprint | 07 |
| INT-002 | Infra outage (`BROWSER_LAUNCH`) | Never labelled `PRODUCT_SUSPECTED` | 07 |
| INT-003 | `AUTOMATION_FLAKE` across seeds | `FLAKY` label with history evidence; override audited | 07 |
| CMP-001 | Compare a PASS run with a PRODUCT_DEFECT run | `@cart-total` is `NEW_FAILURE` per engine; others `STILL_PASSING` | 07 |
| CMP-002 | Case 2× slower in head | Flagged as a duration regression | 07 |

## Quality gate and quarantine
| Test ID | Scenario | Expected result | PRD |
|---|---|---|---|
| CI-001 | All critical tests pass | CLI exit 0 | 08 |
| CI-002 | One critical test fails | CLI exit 1, summary names case and engine | 08 |
| CI-003 | Workers stopped, or CLI deadline reached | Exit 2, never 0 | 08 |
| CI-004 | Bad or missing CI token | Exit 3 | 08 |
| QUA-001 | Quarantine the failing case | FAIL → PASS with `QUARANTINED_FAIL` reported; audit row | 08 |
| QUA-002 | Quarantine expires | Back to FAIL; expiry audited | 08 |

## Cloud, chaos and scheduling
| Test ID | Scenario | Expected result | PRD |
|---|---|---|---|
| CLOUD-001 | No BrowserStack credentials | Local works; cloud option disabled; API `422` | 09 |
| CLOUD-002 | Mock 401/403/429/timeout/partial | Controlled errors and retries; no credential leakage | 09 |
| CHAOS-001 | D1/D2 worker kill × 10 | Replacement attempt or bounded `INFRA_ERROR` every time | 10 |
| CHAOS-002 | D3/D4 duplicates and ack crash × 10 | No contradictory terminal records | 10 |
| CHAOS-003 | D7 zombie worker | Late callback `410`, logged; newer attempt authoritative | 10 |
| CHAOS-004 | Enable chaos outside the local profile | API refuses to start | 10 |
| SCHED-001 | Same workload under FIFO and LPT_AGING | Identical case outcomes; repeatable measurements | 11 |
| SCHED-002 | No duration history | Deterministic fallback; max wait within the documented bound | 11 |
| SHD-001 | Duration-balanced sharding | Every case assigned exactly once per engine | 11 |
| SHD-002 | Shard balance | Predicted spread no worse than round-robin on the fixtures | 11 |

## Security, observability and release
| Test ID | Scenario | Expected result | PRD |
|---|---|---|---|
| SEC-001 | Anonymous or under-scoped mutation and artifact read | `401`/`403`; public demo can't execute | 12 |
| SEC-002 | Canary secret scan of logs, errors, traces, screenshots, spans | Canary never present | 06/12 |
| SEC-003 | gitleaks, Trivy, dependency audit | No unexcepted high or critical findings | 12 |
| OBS-001 | One run traced end to end | Single trace ID across API → AMQP → worker → cases | 12 |
| OBS-002 | Grafana during a run | Queue wait, sessions vs cap, outcomes and outbox lag show live data | 12 |
| REL-001 | Fresh clone, no BrowserStack credentials | `make demo` reproduces pass, fail and recovery | 13 |
| VIS-001 | Deliberate button style change (optional) | Visual diff fails with a visible diff | 14 |
| AI-001 | AI suggestion vs gate (optional) | Outcome and category unchanged by AI text | 15 |

## Required benchmark CSV fields (PRD-11)

`trial_id, experiment, strategy, sharding_mode, shards, random_seed, workload_mix, suite_revision, worker_cap, engine, data_label(synthetic|observed), browser_state(cold|warm), host_cpu, host_ram, os, docker_version, playwright_version, queued_at, finished_at, makespan_ms, median_queue_wait_ms, p95_queue_wait_ms, worker_utilization, failures, notes`

Synthetic data is always labelled `synthetic` and real runs `observed`. A local benchmark supports no claims about cloud scalability.

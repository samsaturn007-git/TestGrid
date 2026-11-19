# PRD INDEX — execute sequentially

One PRD active at a time. `P2 OPTIONAL` PRDs do not block core completion. v2 additions are folded into existing PRDs (see `CHANGES_v2.md`).

| PRD | Title | Predecessor | Effort | Priority | Gate | Tasks |
|---|---|---|---|---|---|---:|
| [PRD-00](../prds/PRD-00-governance.md) | Product definition, ADRs and delivery governance | — | 4–6 h | P0 | G0 | 6 |
| [PRD-01](../prds/PRD-01-monorepo-and-fixture.md) | Monorepo, reproducible local environment, and owned demo shop | PRD-00 | 10–16 h | P0 | G1 | 7 |
| [PRD-02](../prds/PRD-02-domain-and-api.md) | PostgreSQL domain model, REST API, migrations and idempotent run creation | PRD-01 | 14–20 h | P0 | G1 | 6 |
| [PRD-03](../prds/PRD-03-single-worker-runner.md) | Single Playwright worker and end-to-end first run | PRD-02 | 14–22 h | P0 | G1 | 7 |
| [PRD-04](../prds/PRD-04-durable-queue.md) | RabbitMQ, transactional outbox, dispatcher, bounded workers and lease recovery | PRD-03 | 20–30 h | P0 | G2 | 7 |
| [PRD-05](../prds/PRD-05-live-dashboard.md) | React run dashboard, browser matrix, live state and accessible UX | PRD-04 | 14–20 h | P0 | G2 | 7 |
| [PRD-06](../prds/PRD-06-artifacts-and-timeline.md) | Screenshots, Playwright traces, logs, timeline and secure artifact retrieval | PRD-05 | 12–18 h | P0 | G2 | 6 |
| [PRD-07](../prds/PRD-07-failure-intelligence.md) | Failure fingerprints, flaky-test analytics, case history and run comparison | PRD-06 | 16–24 h | P1 | G3 | 7 |
| [PRD-08](../prds/PRD-08-ci-quality-gates.md) | CLI, quality gate, flaky quarantine and GitHub Actions deployment veto | PRD-07 | 14–20 h | P0 | G3 | 7 |
| [PRD-09](../prds/PRD-09-browserstack-adapter.md) | Executor abstraction and BrowserStack Automate adapter (live run optional) | PRD-08 | 8–14 h | P1 | G3 | 7 |
| [PRD-10](../prds/PRD-10-chaos-and-recovery.md) | Chaos lab: prove recovery from real failures | PRD-09 | 12–20 h | P1 | G4 | 6 |
| [PRD-11](../prds/PRD-11-adaptive-scheduler.md) | Duration-aware scheduling, smart sharding and transparent benchmarks | PRD-10 | 16–24 h | P1 | G4 | 7 |
| [PRD-12](../prds/PRD-12-security-and-deploy.md) | Security hardening, observability stack, load baseline and deployable demo | PRD-11 | 16–24 h | P0 | G4 | 6 |
| [PRD-13](../prds/PRD-13-portfolio-and-release.md) | Documentation, reproducible release, demos and resume proof | PRD-12 | 8–12 h | P0 | G5 | 7 |
| [PRD-14](../prds/PRD-14-visual-regression-optional.md) | OPTIONAL: screenshot baselines, visual diffs and approval workflow | PRD-13 | 8–14 h | P2 OPTIONAL | Optional | 5 |
| [PRD-15](../prds/PRD-15-ai-assistance-optional.md) | OPTIONAL: evidence-grounded failure explainer | PRD-13 | 6–12 h | P2 OPTIONAL | Optional | 5 |

## Checkpoint logic
- **G0 (design):** PRD-00.
- **G1 (first run):** PRDs 01–03.
- **G2 (durable platform):** PRDs 04–06.
- **G3 (portfolio MVP):** PRDs 07–09. Cloud adapter can be explicitly **not live-tested** without credentials.
- **G4 (engineering differentiation):** PRDs 10–12.
- **G5 (presentation):** PRD 13.
- **Stretch only:** PRDs 14–15.

Do not start a PRD until its dependencies are `done`. No tasks begin completed.

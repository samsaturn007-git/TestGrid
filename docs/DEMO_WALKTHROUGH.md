# Reviewer demo walkthrough (10 minutes)

> **Status: TARGET, NOT YET RUNNABLE.** This is the acceptance path the finished project must pass (defined in PRD-00 T4). Commands, ports and output below are the *intended* contract; none of them work yet. Each step names the PRD that makes it real and the test ID that proves it. PRD-13 T7 (REL-001) re-runs this exact script from a clean clone.

## Preconditions

- macOS or Linux (Windows via WSL2), Docker engine with ≥ 8 GiB RAM allotted, `git`, `make`. No BrowserStack account, no paid service, no real credentials.
- Toolchain versions as pinned in `docs/VERSIONS.md` (only needed for running tests outside Docker).

## Steps

| # | Time | Action (reviewer types) | Expected result | Made real in | Proof |
|---|---|---|---|---|---|
| 0 | 0:00 | `git clone <repo> && cd testgrid && cp .env.example .env` | Clean checkout; `.env` holds only fake placeholder values. | PRD-01 | AC-4 of PRD-01 |
| 1 | 0:30 | `make up` | `postgres`, `api`, `web`, `demo-shop`, `worker` (and `rabbitmq` from PRD-04) report healthy in under 3 minutes after images are pulled; all ports bound to `127.0.0.1`. | PRD-01 → PRD-04 | PRD-01 NFR timing |
| 2 | 3:00 | `make smoke` | Health checks pass; the `checkout-smoke` spec runs 6 cases against demo-shop on Chromium and all pass. | PRD-01 | PRD-01 AC-1 |
| 3 | 4:00 | Open the dashboard (`http://127.0.0.1:<web-port>`), pick suite `checkout-smoke`, engines Chromium + Firefox + WebKit, fixture `PASS`, submit. | A run appears `QUEUED → RUNNING → PASSED`; live updates via SSE; the browser matrix shows one target per engine, each with 6 passed cases. | PRD-03, PRD-05 | E2E-001, E2E-002 |
| 4 | 5:00 | Submit the same run with fixture `PRODUCT_DEFECT`. | Run `FAILED`; exactly `@cart-total` fails on every engine with `ASSERTION` ("expected total … received …"); screenshot and trace are attached; category `PRODUCT_SUSPECTED`. | PRD-03, PRD-06, PRD-07 | UI-002, INT-001 |
| 5 | 6:00 | Click **Compare with last green**. | `@cart-total` is `NEW_FAILURE` per engine; the other five cases are `STILL_PASSING`. | PRD-07 | CMP-001 |
| 6 | 6:30 | Start a 3-engine run, then `make chaos-kill-worker` (or `docker kill` on one worker) while a target is `RUNNING`. | The killed attempt becomes `ABANDONED` (`LEASE_EXPIRED`) within lease TTL + reaper interval; a new attempt runs on a surviving worker; the run still reaches a terminal state; the timeline shows the heartbeat gap. Active sessions never exceed the cap. | PRD-04, PRD-06, PRD-10 | Q-004, CHAOS-001, Q-001 |
| 7 | 8:00 | `testgrid run --project demo --suite checkout-smoke --engines chromium --fixture PASS --wait; echo $?` then repeat with `--fixture PRODUCT_DEFECT`. | First prints a passing gate summary and exits **0**; second names `@cart-total` on chromium and exits **1**. Stopping workers and re-running with a short `--timeout` exits **2**, never 0. | PRD-08 | CI-001, CI-002, CI-003 |
| 8 | 9:00 | Open the GitHub Actions run linked from the README. | The quality-gate workflow shows a step summary and vetoes the deploy job on a failing gate. | PRD-08 | PRD-08 AC (gate workflow) |
| 9 | 9:30 | `make down` | All containers stop; no data needed outside the repo and Docker volumes. | PRD-01 | — |

## Explicit non-goals the reviewer should NOT expect

(From MASTER_PRD §5; approved together with this walkthrough.)

- No recreation of BrowserStack's commercial grid, device cloud or real devices.
- No arbitrary code, uploaded specs or arbitrary URLs from users.
- Local Playwright WebKit is not claimed to equal Safari on Apple hardware.
- No Kubernetes, billing, organisations, SSO, generative-AI agents or ML schedulers in core scope.
- Not a production multi-tenant SaaS; no promise of zero lost jobs under every failure.
- No copied proprietary code, UI assets or branding.
- BrowserStack live run is optional; without credentials it is mock-tested and labelled unverified.

# PRD-10 — Chaos lab: prove recovery from real failures

**Priority:** P1 | **Estimated effort:** 12–20 h | **Dependencies:** PRD-09 | **Gate:** G4 | **Status:** not started

## Objective
Turn the reliability claims into repeatable experiments. One command injects real faults, checks the system invariants automatically, repeats every drill, and produces a scoreboard and timeline that an interviewer can rerun.

## User stories
- As an interviewer, I run `make chaos DRILL=worker-kill-running REPEAT=10` and get a pass or fail scoreboard with raw logs.
- As the developer, I can show a frozen old worker waking up after its lease expired and being rejected.

## Interview signal
Fault injection, invariant thinking, distributed failure modes, evidence-driven reliability claims.

## Functional requirements
1. **FR-1 Safety gate:** chaos hooks and the `chaos/` runner only work when `TESTGRID_PROFILE=local` and `TESTGRID_CHAOS=enabled`. The API refuses to start with chaos enabled under any other profile, and a test proves it.
2. **FR-2 Drill catalogue** (`chaos/drills/*.ts`, each one: setup → inject → wait → validate → teardown):

   | Drill | Fault | Expected |
   |---|---|---|
   | D1 `worker-kill-claimed` | `SIGKILL` right after claim | attempt `ABANDONED`, retry → terminal |
   | D2 `worker-kill-running` | `SIGKILL` mid-suite | same, with the old attempt's partial evidence kept |
   | D3 `duplicate-delivery` | publish the same message 3 times | one active lease, one terminal result |
   | D4 `complete-before-ack-crash` | `TG_FAULT=after_complete_before_ack` | redelivery acked as terminal; no second attempt |
   | D5 `broker-restart` | `docker compose restart rabbitmq` mid-run | no committed target lost; run terminal |
   | D6 `api-restart` | restart the API during heartbeats | workers back off and retry; leases resume or expire honestly |
   | D7 `zombie-worker` | `SIGSTOP` the worker past its lease, then `SIGCONT` | late `complete` → `410`, logged; the newer attempt is authoritative |
   | D8 `artifact-store-down` | make the store unwritable | results recorded, artifacts `MISSING`, warning shown |
   | D9 `postgres-restart` | restart the DB mid-run | API health degrades then recovers; no contradictory state |
   | D10 `browser-launch-failure` | `TG_FAULT=browser_launch` | `INFRA_ERROR (BROWSER_LAUNCH)`, never classified as a product defect |
3. **FR-3 Invariant validator:** `chaos/validate.ts` runs a SQL check for each of I1–I9 (ARCHITECTURE §5) before and after every drill, and outputs `{invariant, ok, violatingRows}`.
4. **FR-4 Repeat runner:** `--repeat N` (default 10) with a reset and reseed between iterations. It records the pass count, time to recovery (fault → target terminal) and any violations.
5. **FR-5 Outputs:** `docs/evidence/chaos/<date>/scoreboard.md`, `results.json`, and per-iteration raw logs and event timelines. The scoreboard includes the environment block (CPU, RAM, OS, versions).
6. **FR-6 Chaos timeline UI:** `/chaos` lists drill reports from `GET /api/v1/chaos/reports` (read-only), and the run detail timeline shows the fault-injection markers.

## Tasks (in order)
- [ ] **T1** — Add fault injection hooks and profile safety gate with a negative test.
- [ ] **T2** — Implement drills D1–D5 (worker kill, duplicates, ack crash, broker restart).
- [ ] **T3** — Implement drills D6–D10 (API/DB restart, zombie worker, store down, launch failure).
- [ ] **T4** — Build invariant validator I1–I9 and repeat-each-drill runner with recovery timing.
- [ ] **T5** — Expose chaos scoreboard/timeline in UI and exported report.
- [ ] **T6** — Run every drill ≥10 times (or document resource limits), store raw logs and write evidence.

## Acceptance criteria
- [ ] **AC-1:** D1 and D2 finish with a replacement attempt or a bounded `INFRA_ERROR` in every iteration (CHAOS-001).
- [ ] **AC-2:** D3 and D4 never produce contradictory terminal records (CHAOS-002).
- [ ] **AC-3:** In D7 the zombie's late callback is rejected with `410` and logged with its attempt ID (CHAOS-003).
- [ ] **AC-4:** Every drill shows the same invariant result on every repeat, and any failure is reported honestly with its root cause.
- [ ] **AC-5:** Chaos can't be enabled outside the local profile (CHAOS-004).

## Verification
- `make chaos-all REPEAT=10`
- The validator's before and after output
- Spot-check D7 by hand and record the terminal output

## Demo moment
Run D7 live: freeze a worker, watch the reaper reassign its target, unfreeze it, and see `410 lease-expired` in the logs while the scoreboard stays green.

## Risks
- Flaky chaos setup (reset between iterations, explicit waits on conditions rather than sleeps).
- Guarantees that are too optimistic.
- Destructive injection against real data (prevented by the profile gate).

## Out of scope
Public fault injection, unbounded chaos campaigns, network-partition tooling beyond Compose.

## Handoff and definition of done
- Implement **PRD-10 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T6 with tests, then write `docs/evidence/PRD-10.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain each invariant and the zombie-worker case without AI;
  - the tracker is updated only after human review.

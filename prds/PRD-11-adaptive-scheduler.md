# PRD-11 — Duration-aware scheduling, smart sharding and transparent benchmarks

**Priority:** P1 | **Estimated effort:** 16–24 h | **Dependencies:** PRD-10 | **Gate:** G4 | **Status:** not started

## Objective
Show algorithmic reasoning applied to a real system:
- predict durations from history,
- schedule longest-first with starvation protection,
- split suites into duration-balanced shards,
- prove or disprove the benefit with matched, reproducible benchmarks, including the cases where it doesn't help.

## User stories
- As a developer, I run a 15-case suite on 3 duration-balanced shards and the run finishes sooner than with Playwright's file-based sharding, *if* the data shows it.
- As an interviewer, I read the algorithm, its complexity, the fairness guarantee and the raw CSVs, and I can rerun the benchmark myself.
- As a developer, I rerun only the failed cases of a run.

## Interview signal
Heaps, greedy algorithms, LPT scheduling (a well-known approximation result for makespan on identical machines), bin-packing, starvation and aging, experiment design, honest statistics.

## Functional requirements
1. **FR-1 Duration model**
   - `duration_stats` keeps p50 and p90 of the last k=10 successful case durations per `(suite_revision, case_key, engine)`.
   - Updated on `TARGET_FINISHED`.
   - Prediction for a target = sum of the predicted durations of its assigned cases.
   - Fallbacks, in order: the manifest's `expected_duration_ms`, then the suite median, then a global default. The UI shows which source was used: `history`, `manifest` or `default`.
2. **FR-2 Scheduler interface** (API, Java): `Scheduler.select(List<Candidate>, int free, Instant now) → List<Candidate>`, plugged into the PRD-04 dispatcher.
   - `FifoScheduler`: `(queued_at, id)`.
   - `LptAgingScheduler`: a max-heap on `effective = predicted_ms + aging_weight × wait_ms`.
     - Complexity is O(n log n) to build plus O(k log n) per selection, over a bounded candidate window of 500 or fewer.
     - The aging weight guarantees a computable maximum wait. Write down the bound and test it.
   - Switch policy with `PUT /scheduler` (local profile, admin). The policy in force is recorded on every `TARGET_DISPATCHED` event.
3. **FR-3 Smart sharding** (ARCHITECTURE §7)
   - Run request `sharding: {mode, shards 1..4}`.
   - `PLAYWRIGHT_NATIVE` passes `--shard=i/n`.
   - `DURATION_BALANCED` assigns cases with greedy LPT plus a min-heap and writes `target_case_assignments`. The worker builds `--grep` from the assigned tags.
   - Every case runs exactly once per engine.
4. **FR-4 Rerun failed cases:** `POST /runs/{id}/rerun {scope: FAILED_CASES}` creates a run whose targets contain only the failed cases, using case assignment.
5. **FR-5 Workload generator**
   - `bench/workload.ts --seed S` builds a synthetic `synthetic-duration` suite: cases call a demo-shop fixture endpoint that sleeps for a manifest-defined duration class (`S` 1 s, `M` 4 s, `L` 12 s, `XL` 30 s).
   - Mixtures: uniform, heavy-tailed, all-equal (the adverse case).
   - Everything is reproducible from the seed. Data is labelled `synthetic`.
6. **FR-6 Benchmark runner** (`bench/run.ts`)
   - **Experiment A (ordering):** FIFO vs LPT_AGING at caps 1, 2, 3 with N mixed runs queued together.
   - **Experiment B (sharding):** NONE vs PLAYWRIGHT_NATIVE vs DURATION_BALANCED at 2 and 3 shards.
   - At least 5 matched trials per cell with the same seed and warm-up, alternating the strategy order to reduce bias.
   - Output: raw CSV with the fields in `SYSTEM_TEST_MATRIX.md`.
   - At least one `observed` cross-check on the real `catalog-regression` suite.
7. **FR-7 Analysis** (`bench/analyze.py`, matplotlib): median, IQR and p95 for makespan and queue wait, utilisation, box plots per cell, and `docs/benchmarks/RESULTS.md`. That file has the method, environment, results, and a **"where it didn't help"** section.
8. **FR-8 UI** (only if it doesn't delay the evidence): a scheduler settings card, the prediction source on each target, a shard view in run detail, and a small benchmark comparison chart rendered from the committed CSV.

## Tasks (in order)
- [ ] **T1** — Add duration_stats rollup, prediction with fallbacks, and scheduler interface in the dispatcher.
- [ ] **T2** — Implement FIFO and LPT_AGING schedulers with a documented max-wait bound.
- [ ] **T3** — Unit/property tests: ordering, starvation bound, cap and lease invariants unchanged, priority inversion.
- [ ] **T4** — Implement sharding modes with LPT bin-packing, case assignments and rerun-failed-cases.
- [ ] **T5** — Implement reproducible synthetic workload generator and benchmark runner.
- [ ] **T6** — Run ≥5 paired trials per cell; publish raw CSV, plots, method, caveats and honest result.
- [ ] **T7** — Add scheduler/shard UI and benchmark chart if it will not delay core evidence.

## Acceptance criteria
- [ ] **AC-1:** The same workload gives identical case outcomes under every policy and sharding mode (SCHED-001).
- [ ] **AC-2:** The policy changes the dispatch order but never breaches the cap or a lease invariant (validator I1–I5 run during the benchmarks).
- [ ] **AC-3:** With no history, the fallback is deterministic, and a property test shows no target waits longer than the documented bound (SCHED-002).
- [ ] **AC-4:** Duration-balanced sharding assigns every case exactly once, and its predicted shard spread (max − min) is no worse than round-robin on the test fixtures (SHD-001, SHD-002).
- [ ] **AC-5:** The benchmark artifacts include the raw runs, median and IQR per cell, the environment block, and the README states where the scheduler is neutral or worse.

## Verification
- jqwik property tests (Java) for the schedulers; fast-check tests (TypeScript) for bin-packing.
- `make bench SEED=42`, then `python3 bench/analyze.py`.
- Re-run one cell on a cold browser and on a warm one, and report both.

## Demo moment
The box plot: FIFO vs LPT at cap 2 on the heavy-tailed mix, and the all-equal mix where they tie. Explain why.

## Risks
- Benchmark bias (alternate the order, keep the seeds fixed).
- Overfitting to synthetic data (the observed cross-check).
- Starvation (aging plus a tested bound).

## Out of scope
ML prediction, autoscaling, claims about proprietary schedulers.

## Handoff and definition of done
- Implement **PRD-11 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T7 with tests, then write `docs/evidence/PRD-11.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain LPT, aging, bin-packing and their complexity without AI;
  - the tracker is updated only after human review.

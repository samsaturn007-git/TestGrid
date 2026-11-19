# PRD-07 — Failure fingerprints, flaky-test analytics, case history and run comparison

**Priority:** P1 | **Estimated effort:** 16–24 h | **Dependencies:** PRD-06 | **Gate:** G3 | **Status:** not started

## Objective
Give failures explainable, evidence-backed meaning:
- group identical failures,
- label likely categories with transparent rules,
- detect flaky cases from history,
- compare any two runs to show exactly what changed.

All of it is deterministic, evaluated against labelled ground truth, and never presented as root-cause proof.

## User stories
- As an engineer, I see "this is the same failure as the 7 previous ones" with the evidence, instead of 7 separate red rows.
- As an engineer, I see a case's last 20 outcomes and durations on each engine, and why it's labelled flaky.
- As a reviewer, I compare my branch's run with the last green run and get new failures, fixed cases, still-failing cases and duration regressions.
- As an engineer, I override a wrong category, give a reason, and the override is audited.

## Interview signal
String normalisation, hashing, rule engines, sliding-window aggregates, set and map algorithms, evaluation with a confusion matrix, honest uncertainty.

## Functional requirements
1. **FR-1 Fingerprint algorithm `fp_v1`**
   - Normalise the message by stripping ANSI codes and replacing volatile tokens:
     - numbers → `<N>`
     - UUIDs → `<UUID>`
     - hex addresses → `<HEX>`
     - timestamps → `<TS>`
     - URL query strings → dropped
     - temporary paths → `<TMP>`
   - Keep the error class and the top 3 non-framework stack frames as `file:function`, without line numbers.
   - Fingerprint = `sha256(algo_version | error_class | frames | normalised message)[0:16]`.
   - Stored per project with `algo_version` so a future v2 can coexist with v1.
2. **FR-2 Rule classifier:** an ordered table of rules in `api/src/main/resources/failure-rules.yaml`. The first match wins; each rule has a `rule_id` and a base confidence.
   - `R1 SELECTOR_NOT_FOUND` → `TEST_BROKEN`
   - `R2 TEST_TIMEOUT` or a timeout-type case status → `TIMEOUT`
   - `R3 BROWSER_LAUNCH`, `WORKER_LOST` or `NETWORK` → `ENVIRONMENT`
   - `R4 ASSERTION` that passed on retry or has a `FLAKY` history → `AUTOMATION_FLAKE`
   - `R5 ASSERTION` reproduced on at least 2 attempts or runs with the same fingerprint → `PRODUCT_SUSPECTED`
   - Everything else → `UNKNOWN`
   - **Confidence** = the rule's base value, adjusted by how consistent the history is, and capped at 0.95. The UI shows the matched rule and its evidence, never just a bare label.
3. **FR-3 Human override:** `PUT /failure-groups/{id}/override {category, reason}` stores who, why and when, plus an `audit_events` row. Overrides stick for that fingerprint version.
4. **FR-4 Case health:** a rolling window of the last N=20 outcomes per `(suite_key, case_key, engine)`.
   - `flip_rate = transitions / (n − 1)`.
   - Labels: `FLAKY` if n ≥ 5, both outcomes are present and flip_rate ≥ 0.2 (or `FLAKY` statuses ≥ 2); `FAILING` if the last 3 all failed; `STABLE` otherwise; `INSUFFICIENT_DATA` if n < 5.
   - Recomputed incrementally on `TARGET_FINISHED`. The window and thresholds are configurable.
5. **FR-5 Case history:** `GET /cases/{caseKey}/history` feeds a UI page with an outcome strip and a duration sparkline per engine, linking to each run.
6. **FR-6 Run comparison** (contracts §8)
   - `GET /runs/compare?base=&head=`, including the `last-green` shortcut.
   - Built in O(n) with a map keyed by `(caseKey, engine)`.
   - The UI page `/compare?base=&head=` groups results by category, highlights fingerprint changes and duration regressions, and is reachable from run detail through "Compare with last green".
7. **FR-7 Failure groups UI:** `/failures` lists groups by occurrence count with category and confidence. The detail view shows the normalised message, raw examples, occurrences across runs and engines, linked artifacts, the rule explanation and the override form.
8. **FR-8 Evaluation harness**
   - `scripts/eval-failures.ts` seeds the 6 labelled scenarios (the 5 demo-shop fixture modes plus an injected `BROWSER_LAUNCH` fault), each run 5 or more times.
   - It compares the predicted categories to the labels and writes `docs/evidence/data/failure-eval.csv` and a confusion matrix in markdown.

## Tasks (in order)
- [ ] **T1** — Implement `fp_v1` normalization and hashing with unit tests incl. collision and stability cases.
- [ ] **T2** — Implement rule-table classifier with confidence, evidence and audited human override.
- [ ] **T3** — Implement rolling case-health window, labels and case history API/UI.
- [ ] **T4** — Implement run comparison API with last-green shortcut and compare UI.
- [ ] **T5** — Build failure groups list/detail UI with evidence and occurrences.
- [ ] **T6** — Create seeded truth dataset and evaluation harness.
- [ ] **T7** — Publish confusion matrix, precision/recall per category and known ambiguous cases.

## Acceptance criteria
- [ ] **AC-1:** The same seeded failure repeated 5 times maps to one fingerprint, even though order numbers and timestamps differ (INT-001).
- [ ] **AC-2:** Different seeded failures are not merged into one catch-all signature, and an infrastructure fault is never labelled `PRODUCT_SUSPECTED` (INT-002).
- [ ] **AC-3:** The `AUTOMATION_FLAKE` fixture, run across seeds, produces a `FLAKY` label linked to its history; an override changes the displayed category and writes an audit row (INT-003).
- [ ] **AC-4:** Comparing a PASS run (base) with a PRODUCT_DEFECT run (head) returns `@cart-total` as `NEW_FAILURE` on every engine and every other case as `STILL_PASSING`. A case slowed by 2× is flagged as a regression (CMP-001, CMP-002).
- [ ] **AC-5:** The evaluation script produces a real confusion matrix from the fixtures; the evidence includes the raw CSV.

## Verification
- Unit tests for normalisation, the rules and the window maths. Property test: normalisation is idempotent (`n(n(x)) = n(x)`).
- Integration tests for comparison and history.
- `pnpm tsx scripts/eval-failures.ts`

## Demo moment
Compare a branch run against last-green: one red `NEW_FAILURE`, its fingerprint group showing 5 earlier occurrences, the matched rule and the evidence.

## Risks
- Claiming causal certainty (the UI says "likely", shows the rule, and allows an override).
- Fingerprint collisions.
- Small sample sizes (labelled `INSUFFICIENT_DATA`).

## Out of scope
LLM calls (optional in PRD-15), ML classifiers, cross-project grouping.

## Handoff and definition of done
- Implement **PRD-07 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T7 with tests, then write `docs/evidence/PRD-07.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain normalisation, the flaky window and the comparison complexity without AI;
  - the tracker is updated only after human review.

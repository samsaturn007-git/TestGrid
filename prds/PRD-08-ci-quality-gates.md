# PRD-08 — CLI, quality gate, flaky quarantine and GitHub Actions deployment veto

**Priority:** P0 | **Estimated effort:** 14–20 h | **Dependencies:** PRD-07 | **Gate:** G3 | **Status:** not started

## Objective
CI can submit a run, wait for it, and block a merge or deploy with the correct exit code. The quality policy is versioned and evaluated on the server. Flaky cases can be quarantined in a controlled, audited, expiring way, and infrastructure failures are never reported as test results.

## User stories
- As a developer, my PR check fails when a critical checkout case fails, and the job summary tells me which case, on which engine, with a link.
- As a developer, when the worker fleet is down my check says **indeterminate (infra)**, not "tests failed" and never "passed".
- As a team lead, I quarantine a known-flaky case for 7 days with a reason. The gate stops blocking on it but still reports it, and the quarantine expires automatically.

## Interview signal
Policy-as-data, exit-code contracts, polling with backoff and deadlines, GitHub Actions security (fork PRs, permissions), audited gate bypass.

## Functional requirements
1. **FR-1 Policy model:** versioned policy JSON (contracts §12).
   - `GET/PUT /projects/{id}/quality-policy`; `PUT` always creates a new version.
   - The evaluation records the policy version it used.
2. **FR-2 Evaluator**
   - Runs automatically on `RUN_FINISHED` and is idempotent through `UNIQUE(run_id, policy_id)`.
   - Rules: `required-targets`, `critical-cases`, `max-failed-cases`, `infra-error`, `max-flaky-rate`, `duration-regression` (uses the PRD-07 comparison against last green), `quarantine-honored`.
   - Each rule result is `{ruleId, observed, threshold, outcome, blocking, evidence[]}`, and the overall outcome follows contracts §2.4.
3. **FR-3 Quarantine**
   - `POST /projects/{id}/quarantines {caseKey, engine?, reason, expiresAt ≤ 30d}` and `DELETE /quarantines/{id}`; every change writes an audit event.
   - Policy `autoMode`:
     - `SUGGEST`: the UI shows a "Quarantine?" prompt on `FLAKY` cases.
     - `AUTO`: only when the case is `FLAKY` with n ≥ 10; expires in 7 days.
   - A quarantined failure is reported as `QUARANTINED_FAIL` and doesn't count toward FAIL. `maxActive` caps how many quarantines can be active at once.
4. **FR-4 CLI** (`cli/`, contracts §11)
   - Commands: `run`, `status`, `gate`, `compare`.
   - `--wait` polls with exponential backoff (1 s → 10 s, ±20 % jitter) until the `--timeout` deadline. On the deadline it returns exit 2, never 0.
   - Prints a readable table, or exactly one JSON document with `--json`.
   - The idempotency key is derived from repo, commit, CI ref, suite and engines, so retrying a job doesn't duplicate the run.
5. **FR-5 CI token:** a minimal `api_tokens` table with a CI token scoped to `runs:write runs:read quality:read`. It's sent as `Authorization: Bearer`, and write endpoints reject requests without it (PRD-12 extends this to every user-facing write).
6. **FR-6 GitHub Actions** `testgrid-gate.yml`
   - Runs on `pull_request` (never `pull_request_target`) with `permissions: contents: read`.
   - Starts the whole stack inside the GitHub-hosted runner with Compose. Nothing is exposed publicly, and fork PRs get no secrets because none are needed.
   - Runs `testgrid run --wait`, writes a markdown summary to `$GITHUB_STEP_SUMMARY` (rule table plus failed cases), and uploads failure artifacts with `actions/upload-artifact`.
   - A `deploy-demo` job uses `needs: gate`, so a FAIL or INDETERMINATE vetoes it.
7. **FR-7 UI:** a quality panel on run detail with the rule table and the policy version; `/projects/:id/policy` shows the policy and its version history; `/quarantines` lists them with expiry countdowns.

## Tasks (in order)
- [ ] **T1** — Implement versioned policy model, evaluator, rule results and boundary tests.
- [ ] **T2** — Implement quarantine API, expiry, auto/suggest modes and audit events.
- [ ] **T3** — Build CLI with run/status/gate/compare, polling deadline and documented exit codes.
- [ ] **T4** — Add CI-scoped API token and protect write endpoints used by the CLI.
- [ ] **T5** — Create GitHub Actions gate workflow with step summary, artifacts and deploy veto.
- [ ] **T6** — Build quality panel, policy and quarantine UI.
- [ ] **T7** — Write passing/failing/infra/quarantined scripted examples and document CI security.

## Acceptance criteria
- [ ] **AC-1:** A green fixture gives CLI exit 0 and a green check (CI-001).
- [ ] **AC-2:** `PRODUCT_DEFECT` on a critical case gives exit 1, a failed check, and a summary naming the case and engine (CI-002).
- [ ] **AC-3:** Stopped workers or the CLI deadline give exit 2; a bad token gives exit 3; neither ever gives 0 (CI-003, CI-004).
- [ ] **AC-4:** Quarantining the failing case flips the outcome from FAIL to PASS with `QUARANTINED_FAIL` in the report. Once the quarantine expires, it's FAIL again. Both changes are audited (QUA-001, QUA-002).
- [ ] **AC-5:** The workflow has `contents: read` permissions and SHA-pinned actions, a fork PR gets no secrets and can't run code that isn't in the manifest, and the secret scan is clean.

## Verification
- Policy unit matrix, including boundary equality (`failed == max`).
- A local `act` or real GitHub run for all 4 scenarios, with run links.
- A permissions audit of the workflow YAML.

## Demo moment
Open a PR with the defect fixture: red check with a summary table. Quarantine the case: green with a "1 quarantined failure" note. A deploy job blocked on red.

## Risks
- Quarantine used to hide real bugs (expiry, the cap, audit and visible reporting prevent this).
- Secret exposure.
- Infinite polling (prevented by the deadline).

## Out of scope
A GitHub App, Checks API annotations beyond the job summary, marketplace publishing, paid hosting.

## Handoff and definition of done
- Implement **PRD-08 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T7 with tests, then write `docs/evidence/PRD-08.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain the exit codes, the quarantine safeguards and the fork-PR security without AI;
  - the tracker is updated only after human review.

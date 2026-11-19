# PRD-13 — Documentation, reproducible release, demos and resume proof

**Priority:** P0 | **Estimated effort:** 8–12 h | **Dependencies:** PRD-12 | **Gate:** G5 | **Status:** not started

## Objective
Package the system so an engineer can verify it in under ten minutes and question its trade-offs, with every claim linked to code, tests or data.

## User stories
- As a recruiter, I understand what TestGrid is and why it's impressive from the README alone in 60 seconds.
- As an engineer, I clone the repository, run `make demo`, and reproduce a passing run, a failing run, a worker-kill recovery and a CI gate in 10 minutes.

## Interview signal
Communication, honesty, reproducibility, product sense.

## Functional requirements
1. **FR-1 README** (structure in `docs/PORTFOLIO_PLAYBOOK.md`):
   - a one-line value statement and a 30-second GIF;
   - "what this demonstrates";
   - quick start, architecture diagram, correctness proof, benchmark results, security model, an honest feature matrix and limitations.
   - Badges only for things that are real (CI status, licence).
2. **FR-2 `make demo`:** seeds and runs the four reviewer scenarios (pass, product defect, flaky with a quarantine suggestion, a worker-kill drill) and prints the dashboard, Jaeger and Grafana URLs. It needs no credentials.
3. **FR-3 Evidence hub:** `docs/EVIDENCE_INDEX.md` links every PRD evidence file, the chaos scoreboard, benchmark CSVs and plots, the failure-classification confusion matrix, load results and CI runs.
4. **FR-4 Media:** a 3–4 minute demo video following the script, plus screenshots of the dashboard, run detail, failure trace, comparison view, quality gate, Grafana, Jaeger, chaos scoreboard and benchmark plot. All redacted.
5. **FR-5 API and operations docs:** rendered OpenAPI (Redoc static page), `docs/OPERATIONS.md` troubleshooting, and accessibility notes.
6. **FR-6 Honest feature matrix:** Complete / Partial / Not implemented. It includes the BrowserStack live status, WebKit vs Safari, and single-node limits.
7. **FR-7 Interview kit:** an architecture one-pager, a Q&A on the 12+ defence questions linked to source files, and 3–4 truthful resume bullets containing only measured numbers.
8. **FR-8 Release:** a `v1.0.0` tag, a GitHub Release with notes, an SBOM (for example Syft, if adopted in PRD-12), and checksums.

## Tasks (in order)
- [ ] **T1** — Write reviewer-first README and architecture decision summary.
- [ ] **T2** — Build `make demo` reviewer path and evidence index.
- [ ] **T3** — Create screenshots, GIF and 3–4 minute demo video; redact secrets.
- [ ] **T4** — Publish raw performance, chaos and classification evidence with links.
- [ ] **T5** — Generate API docs, operations/troubleshooting page and accessibility notes.
- [ ] **T6** — Write honest feature matrix, interview one-pager/Q&A and truthful resume bullets.
- [ ] **T7** — Run from-clean-clone reviewer acceptance test and tag the release.

## Acceptance criteria
- [ ] **AC-1:** A reviewer (or a clean container) follows the setup and reproduces a successful and a failing run plus a recovery (REL-001).
- [ ] **AC-2:** Every link to code and evidence resolves (checked by a link-checker run), and the media contains no secrets.
- [ ] **AC-3:** The documented limitations match the implementation and the reality of the cloud account.
- [ ] **AC-4:** Every resume bullet and README number traces to a committed file.

## Verification
- A fresh checkout on a separate machine or a clean container
- `lychee` or a similar link check
- A manual 10-minute dry run, timed

## Demo moment
The whole video. It follows the PORTFOLIO_PLAYBOOK script.

## Risks
- Marketing overclaims.
- Missing demo dependencies.
- Broken release instructions.

## Out of scope
New engineering features; only blocker bugs get fixed.

## Handoff and definition of done
- Implement **PRD-13 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T7, then write `docs/evidence/PRD-13.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can present the whole system without AI;
  - the tracker is updated only after human review.

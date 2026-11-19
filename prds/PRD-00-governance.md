# PRD-00 — Product definition, ADRs and delivery governance

**Priority:** P0 | **Estimated effort:** 4–6 h | **Dependencies:** none | **Gate:** G0 | **Status:** not started

## Objective
Before any application code exists, freeze a small, buildable scope, record the important decisions with the options that were rejected, and set up the repository rules so every later PRD can be checked against evidence.

## User stories
- As the solo engineer, I want written constraints and acceptance gates so an AI agent cannot quietly widen the scope.
- As an interviewer, I want to read the main trade-offs in under five minutes and see that they were decided before coding started.

## Interview signal
Architecture reasoning, trade-off writing, risk management, and professional repository hygiene.

## Functional requirements
1. **FR-1 Repository bootstrap**
   - `git init`, the default branch, and `.gitignore` for Java, Node and environment files.
   - `.github/pull_request_template.md` with the evidence checklist.
   - `docs/decisions/README.md` (the ADR index).
2. **FR-2 ADRs** (follow `docs/decisions/ADR_TEMPLATE.md`):
   - ADR-001 Stack: Spring Boot API, Node Playwright worker, Postgres as canonical state. Rejected: Node-only, Go, MongoDB.
   - ADR-002 Phased delivery: single polling worker first, then RabbitMQ with a transactional outbox. Rejected: RabbitMQ from day one, Kafka, Redis streams, DB-only polling forever.
   - ADR-003 Safe execution boundary: manifest-only suites and no client URLs or code. Rejected: uploaded specs, Git URL runners.
   - ADR-004 Dispatch and scheduling ownership: the DB dispatcher decides order and the broker only transports (ARCHITECTURE §3). Rejected: broker FIFO plus consumer-side priority, RabbitMQ priority queues, pure DB polling.
3. **FR-3 Vocabulary sign-off:** confirm the canonical enums and run-aggregate truth table in contracts §2. Record a conflict register (see the task T1 notes) showing that every term means the same thing across MASTER, ARCHITECTURE, CONTRACTS, SECURITY and the PRDs.
4. **FR-4 Risk register:** `docs/RISKS.md` with the top 8 risks. Each one gets likelihood, impact, mitigation, the PRD that verifies it, and a test ID from `SYSTEM_TEST_MATRIX.md`.
5. **FR-5 Demo walkthrough:** `docs/DEMO_WALKTHROUGH.md` with the reviewer's 10-minute path. It starts from a clean clone, runs a passing run, a product defect, a worker-kill recovery and a CI gate, and states the expected result of each step. Mark it *target, not yet runnable*.
6. **FR-6 Version and resource budget:** `docs/VERSIONS.md` listing the exact versions to pin, all **checked against the official sources on the day of writing**:
   - JDK, Spring Boot, Node LTS, pnpm, Playwright and its Docker image tag, Postgres, RabbitMQ.
   - The resource budget: the development machine's actual RAM and CPU, the per-service memory limit, the default worker count (≤ 3), and the browser concurrency cap.

## Tasks (in order)
- [ ] **T1** — Read and confirm MASTER_PRD plus contract and security documents; record the conflict register.
- [ ] **T2** — Create ADR-001 to ADR-004 with considered alternatives.
- [ ] **T3** — Write the risk register (top 8) with mitigation and verifying test IDs.
- [ ] **T4** — Define the clean-checkout demo acceptance walkthrough.
- [ ] **T5** — Confirm version pinning and resource-budget limits from official sources.
- [ ] **T6** — Bootstrap the repository: PR template, ADR index, branch and commit conventions.

## Acceptance criteria
- [ ] **AC-1:** Each ADR names at least two rejected alternatives with a concrete reason, and states what it does *not* claim.
- [ ] **AC-2:** The conflict register shows zero open contradictions between master, architecture, contracts, security and the PRDs.
- [ ] **AC-3:** The developer has approved the demo walkthrough and the non-goals (recorded in the evidence file).
- [ ] **AC-4:** Every version in `VERSIONS.md` has a source link and the date it was checked. No version is invented.

## Verification
- `python3 tools/progress.py status` shows the dependency order.
- Grep check: every enum in contracts §2.1 appears with the same spelling in the PRDs and docs. Show the commands and output.
- A manual review of the links.

## Demo moment
None. This is the "why" slide in the final README.

## Risks
- Over-scoping through AI suggestions.
- Success measures that can't be measured.
- Pinning versions from memory instead of official sources.

## Out of scope
Any application code, Compose files or CI workflows (those start in PRD-01).

## Handoff and definition of done
- Implement **PRD-00 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T6, then write `docs/evidence/PRD-00.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - the developer can explain each ADR without AI;
  - the tracker is updated only after human review.

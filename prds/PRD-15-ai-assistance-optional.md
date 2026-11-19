# PRD-15 — OPTIONAL: evidence-grounded failure explainer

**Priority:** P2 OPTIONAL | **Estimated effort:** 6–12 h | **Dependencies:** PRD-13 | **Gate:** Optional | **Status:** not started

## Objective
Add an opt-in, clearly labelled AI summary of a failure group. It must cite its evidence and can never change a status, category or gate outcome. Deterministic classification (PRD-07) remains the ground truth.

## User stories
- As an engineer, I click "Explain (AI suggestion)" on a failure group and get a short summary that cites the attempt, case, log lines and artifacts it used, with an uncertainty note.
- As the owner, I can turn the feature off, run it offline with a mock, and cap its token cost.

## Functional requirements
1. **FR-1 Privacy and consent:** off by default and enabled per project. A settings page explains exactly what gets sent.
2. **FR-2 Redaction and bounds:** reuse the PRD-06 redactor, cap the input (for example 8 KB of evidence), and never send raw traces or screenshots.
3. **FR-3 Prompt and response schema:**
   - Evidence goes in as structured JSON with source IDs.
   - The response is JSON with `summary`, `suspectedCategory`, `citations[sourceId]` and `confidence`, validated against a schema. A response with an invalid citation is discarded.
4. **FR-4 Provider interface:** `Explainer` with `MockExplainer` (offline, deterministic) and one opt-in provider adapter, with the API key from the environment only. Timeouts, a cost cap and a daily request cap.
5. **FR-5 UI:** an "AI-generated suggestion — verify with evidence" banner, citation links into the artifacts, and thumbs up or down feedback that is stored.
6. **FR-6 Evaluation:** run against the PRD-07 labelled dataset and a prompt-injection fixture (a log line telling the model to ignore its instructions). Report agreement with the deterministic labels and any unsupported claims.

## Tasks (in order)
- [ ] **T1** — Write threat/privacy review and consent/settings.
- [ ] **T2** — Build redaction and evidence-bounded prompt/response schema.
- [ ] **T3** — Implement offline mock and opt-in provider adapter with cost caps.
- [ ] **T4** — Show linked sources/confidence and feedback controls.
- [ ] **T5** — Evaluate cases incl. prompt injection and document unsupported conclusions.

## Acceptance criteria
- [ ] **AC-1:** Without a key, the project works and the feature is off.
- [ ] **AC-2:** Logs sent to the model are redacted and bounded, verified by a test that captures the outgoing request.
- [ ] **AC-3:** AI text can't change a test outcome, category or quality gate (AI-001).
- [ ] **AC-4:** The UI says "suggestion — verify with evidence", and responses with invalid citations are discarded.

## Verification
- Redaction unit tests
- The prompt-injection fixture
- An offline mock evaluation

## Risks
- Privacy leakage.
- Cost.
- Overclaiming novelty (BrowserStack already ships AI failure analysis).

## Out of scope
Autonomous agents, automatic code fixes, auto-merged changes.

## Handoff and definition of done
- Implement **PRD-15 only**, and only after PRD-13 is done. Follow AGENTS.md and write `docs/evidence/PRD-15.md` with proof for every AC.
- The tracker is updated only after human review.

# PRD-14 — OPTIONAL: screenshot baselines, visual diffs and approval workflow

**Priority:** P2 OPTIONAL | **Estimated effort:** 8–14 h | **Dependencies:** PRD-13 | **Gate:** Optional | **Status:** not started

## Objective
Add a modest visual-regression workflow on top of Playwright's own screenshot comparison. TestGrid contributes baseline management, storage, a three-pane review UI and an audited approval step. It's only worth doing once the core product is stable.

## User stories
- As a test author, I record approved baselines per engine, viewport and fixture state, and see a diff when the UI changes.
- As a reviewer, I approve or reject a changed screenshot, and the decision is permissioned and audited.

## Functional requirements
1. **FR-1** Choose and document a diff approach: Playwright's `toHaveScreenshot` output, or a library such as pixelmatch or odiff, with licences checked.
2. **FR-2 Baselines**
   - Keyed by `(suite_key, case_key, snapshot_name, engine, viewport, fixture_mode)`.
   - Versioned and stored through `ArtifactStore`.
   - Approval creates a new version; the old one is kept.
3. **FR-3 Stable capture:** a pinned container image, fonts, viewport, animations disabled and fixture state reset. Rendering-noise thresholds are configurable and stored with each comparison.
4. **FR-4 UI:** baseline, current and diff panes with a slider overlay. Approve or reject needs the `baseline:write` scope and a reason, and writes an audit event.
5. **FR-5 Gate integration:** an optional policy rule, `visual-diff`, which doesn't block by default.

## Tasks (in order)
- [ ] **T1** — Choose and document image-diff approach and licenses.
- [ ] **T2** — Implement baseline schema, versioning and storage.
- [ ] **T3** — Implement deterministic diff evaluation and thresholds.
- [ ] **T4** — Build 3-pane UI with permissioned approval and audit log.
- [ ] **T5** — Test stable, noisy and real-regression fixtures.

## Acceptance criteria
- [ ] **AC-1:** The same fixture's baseline passes 5 times in a row.
- [ ] **AC-2:** A deliberate change to button colour or layout fails with a visible diff (VIS-001).
- [ ] **AC-3:** Approval is permissioned, audited and versioned.

## Verification
- Golden-image E2E
- A browser-version mismatch test
- Authorisation tests

## Risks
- Rendering noise.
- Disk usage.
- Claiming equivalence with Percy or Argos.

## Out of scope
Pixel-perfect equivalence across operating systems, AI visual reasoning.

## Handoff and definition of done
- Implement **PRD-14 only**, and only after PRD-13 is done. Follow AGENTS.md and write `docs/evidence/PRD-14.md` with proof for every AC.
- The tracker is updated only after human review.

# PRD-05 — React run dashboard, browser matrix, live state and accessible UX

**Priority:** P0 | **Estimated effort:** 14–20 h | **Dependencies:** PRD-04 | **Gate:** G2 | **Status:** not started

## Objective
Make real execution data easy to understand at a glance and live, without ever showing a stale or invented status, and make it fully usable with a keyboard.

## User stories
- As a developer, I launch a suite on three engines and watch the targets move from queued to claimed, running and finished, live.
- As a developer, I filter runs by status, suite and branch, share the URL, and the teammate who opens it sees the same view.
- As a developer, I rerun only the failed targets of a run with one click.
- As a keyboard-only user, I can do all of the above without a mouse.

## Interview signal
Server state versus client state, SSE with replay, cache invalidation, accessibility, component testing, testing your own UI with Playwright.

## Functional requirements
1. **FR-1 App shell**
   - React Router, TanStack Query, generated API types from `packages/contracts`.
   - Tailwind design tokens: colour, spacing and type scale defined once.
   - A skip link, landmarks, and a visible focus ring.
2. **FR-2 Pages**
   - `/` Overview: status counts for the last 24 h, recent runs, workers online. Every number comes from the API.
   - `/projects/:id/suites`: suite cards showing case count, revision and allowed engines.
   - New-run dialog: suite, engines, fixture mode (local profile only), sharding placeholder hidden until PRD-11.
   - `/runs`: run list with filters that sync to the URL, cursor "Load more", and empty, loading and error states.
   - `/runs/:id`: header with aggregate status, trigger, commit and branch, cancel and rerun buttons.
     - The **browser matrix** is cases × engines, each cell a status chip showing the latest attempt.
     - A per-target attempts list with timestamps; an expandable case table sortable by status and duration.
   - `/workers`: worker registry with state, slots in use and time since last seen.
3. **FR-3 Live updates**
   - The `useRunEvents(runId)` hook uses `EventSource`. Each event updates the TanStack Query cache.
   - On reconnect it resumes from `Last-Event-ID`; on `RESYNC` or any parse error it refetches the full snapshot.
   - After 2 failed reconnects it falls back to polling every 5 s, with a visible "Live updates paused, refreshing every 5s" banner.
4. **FR-4 SSE server** (`GET /runs/{id}/events`)
   - Spring `SseEmitter` with a cap on concurrent emitters and a 15 s ping.
   - Replays from `run_events WHERE id > lastEventId`.
   - New events are triggered by Postgres `LISTEN/NOTIFY` published in the state-change transaction.
5. **FR-5 Status display:** one `StatusBadge` component maps every enum value to an icon, a text label and `aria-label`. Colour is never the only signal. Labels exactly match `TargetStatus` and `RunStatus`.
6. **FR-6 Rerun:** `POST /runs/{id}/rerun {scope: FAILED_TARGETS | ALL, idempotencyKey}` creates a linked run (`trigger=RERUN`, `parent_run_id`). The UI shows the link in both directions.
7. **FR-7 Honest data:** no seeded fake runs in the dashboard. If there's no data, show an empty state that tells the user how to create a run.

## Tasks (in order)
- [ ] **T1** — Build accessible app shell, routing, design tokens and StatusBadge for every status.
- [ ] **T2** — Connect suites view and new-run dialog posting with an idempotency key.
- [ ] **T3** — Build run list with URL-synced filters and cursor pagination; add API filters.
- [ ] **T4** — Build run detail: browser matrix, attempts list, case table, cancel and rerun.
- [ ] **T5** — Implement SSE endpoint (replay, NOTIFY, ping) and client hook with reconnect, resync and polling fallback.
- [ ] **T6** — Build overview and workers pages from real API data.
- [ ] **T7** — Add Vitest/Testing Library unit tests, axe accessibility checks and Playwright E2E for the dashboard.

## Acceptance criteria
- [ ] **AC-1:** A run created from the UI appears and progresses live with no manual refresh and no DB edits.
- [ ] **AC-2:** The UI distinguishes `PASSED, FAILED, TIMED_OUT, INFRA_ERROR, CANCELLED` (and in-progress states) by text and icon. A component test covers every enum value.
- [ ] **AC-3:** Killing the SSE connection mid-run (by blocking the request in a Playwright test) settles on the correct DB state after reconnect, with no stale green status (UI-001).
- [ ] **AC-4:** axe reports no serious or critical violations on all pages, and the keyboard-only E2E path works: create run → open detail → cancel.
- [ ] **AC-5:** Rerunning failed targets creates a linked run that contains only the failed targets (UI-003).

## Verification
- `pnpm --filter web test`
- `pnpm --filter web e2e`, where the dashboard's own Playwright E2E shows deterministic PASS then FAIL (UI-002)
- An SSE replay integration test in the API

## Demo moment
Three engines filling in live. Unplug the network in DevTools, plug it back in: the dashboard catches up correctly.

## Risks
- Stale events overwriting newer cache data (compare event IDs, ignore older ones).
- Fake metrics.
- Polling overload (fallback only after SSE fails).

## Out of scope
Charts beyond counts, flakiness intelligence, artifacts (PRD-06), comparison (PRD-07).

## Handoff and definition of done
- Implement **PRD-05 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T7 with tests, then write `docs/evidence/PRD-05.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain SSE replay and cache reconciliation without AI;
  - the tracker is updated only after human review.

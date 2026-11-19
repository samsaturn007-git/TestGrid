# PRD-09 — Executor abstraction and BrowserStack Automate adapter (live run optional)

**Priority:** P1 | **Estimated effort:** 8–14 h | **Dependencies:** PRD-08 | **Gate:** G3 | **Status:** not started

> **Required vs optional:** the executor abstraction, the mock adapter and the contract tests are **required**. A *live* BrowserStack run is optional and depends on having an account. Without credentials the PRD can still close, with the live path explicitly marked "not live-tested".

## Objective
Show you understand the product by sending supported runs to BrowserStack Automate through a clean executor interface, and mapping the remote builds and sessions to TestGrid's model honestly.

## User stories
- As a developer, I choose `LOCAL` or `BROWSERSTACK` per target from allowlisted capability profiles, and see a "Cloud" badge with a link to the session.
- As a developer without credentials, the cloud option is visibly disabled with an explanation, and everything local still works.

## Interview signal
Interface design, adapters, external API resilience (retries, 429 handling, timeouts), secret handling, contract tests against a mock.

## Functional requirements
1. **FR-1 Research first** (T1): read the official BrowserStack docs on the day of implementation and record in `docs/integrations/browserstack.md`:
   - the current SDK package and configuration (`browserstack.yml`);
   - the Playwright REST endpoints for builds and sessions;
   - authentication, rate limits and the session status values.
   No endpoint or field is hard-coded from memory.
2. **FR-2 Executor interface** (worker):
   - `Executor { id; supports(target); execute(ctx, signal): Promise<ExecutionResult> }`
   - `LocalPlaywrightExecutor` is refactored from PRD-03 with no behaviour change; the existing tests stay green.
3. **FR-3 Capability profiles:** manifest entries such as `bs-win11-chrome-latest` and `bs-mac-safari-latest`. Clients choose a `profileKey` from the allowlist, never raw capabilities. `target_key = browserstack:<profileKey>:0`.
4. **FR-4 BrowserStackExecutor**
   - Launches the same registered suite through the official SDK with the selected profile.
   - Records the build ID and session IDs (`POST /internal/v1/attempts/{id}/external-session`).
   - Marks the session pass or fail on BrowserStack where the API supports it.
   - **Budget guard:** `BROWSERSTACK_MAX_SESSIONS_PER_DAY` and a per-run session cap; when exceeded, the executor refuses with `CONFIG`.
5. **FR-5 Metadata client** (API)
   - Fetches build and session details with a token-bucket rate limiter, retries on `429` honouring `Retry-After`, and treats `401/403` as `CONFIG`.
   - Timeout of 10 s or less. Stores the results in `external_sessions`.
6. **FR-6 Status mapping:** a documented table from remote status to `TargetStatus`. A remote error with no test result is `INFRA_ERROR`, never `PASSED`. Cloud devices are labelled by profile, never squeezed into the local engine names.
7. **FR-7 Mock server** (`worker/test/mocks/browserstack`): a small HTTP mock with scenarios for success, `401`, `403`, `429` then success, timeout, and a partial session list. Contract tests run in CI.
8. **FR-8 UI:** profile selector (disabled without credentials, with a tooltip saying why), Cloud badge, external session link, and specific error messages for auth, quota and rate-limit failures.
9. **FR-9 Secrets:** `BROWSERSTACK_USERNAME` / `BROWSERSTACK_ACCESS_KEY` come only from the environment. They are redacted in logs (the PRD-06 redactor) and never stored in the DB or in artifacts.

## Tasks (in order)
- [ ] **T1** — Research and document current BrowserStack SDK and REST API from official docs.
- [ ] **T2** — Create executor abstraction and refactor local executor; add capability-profile allowlist.
- [ ] **T3** — Build HTTP mock and contract tests for success/401/403/429/timeout/partial.
- [ ] **T4** — Implement opt-in SDK launch with budget guard and external-session recording.
- [ ] **T5** — Implement metadata client with rate limiting/retries and status mapping table.
- [ ] **T6** — Add UI profile selector, cloud badge, session links and error messages.
- [ ] **T7** — Record live evidence if credentials exist; otherwise mark the live path unverified.

## Acceptance criteria
- [ ] **AC-1:** Without credentials, local mode works, the cloud option is disabled with an explanation, and the API rejects `BROWSERSTACK` targets with `422` (CLOUD-001).
- [ ] **AC-2:** The mock contract tests pass for success, 401/403, 429 with Retry-After, timeout and partial sessions (CLOUD-002).
- [ ] **AC-3:** With live credentials, a run links to a verified build and session. Without them, the evidence says "not live-tested" and includes the exact steps to verify.
- [ ] **AC-4:** A repository, DB and log scan finds no credentials. The budget guard refuses past the cap.

## Verification
- `pnpm --filter worker test:contract`
- A grep and log scan for credentials
- An optional live smoke run with a low session budget

## Demo moment
The same suite, one target local Chromium and one target BrowserStack Windows Chrome, side by side in the matrix, with a link to the cloud session.

## Risks
- Pricing and access limits.
- SDK API drift (pin the version and re-check the docs).
- Running out of quota.

## Out of scope
Replacing BrowserStack infrastructure, impersonating devices, re-hosting remote videos or traces.

## Handoff and definition of done
- Implement **PRD-09 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T7 with tests, then write `docs/evidence/PRD-09.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain the adapter boundary and the 429 handling without AI;
  - the tracker is updated only after human review.

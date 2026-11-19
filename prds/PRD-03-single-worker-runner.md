# PRD-03 — Single Playwright worker and end-to-end first run

**Priority:** P0 | **Estimated effort:** 14–22 h | **Dependencies:** PRD-02 | **Gate:** G1 | **Status:** not started

## Objective
A real browser runs a real registered suite. Its per-case results are stored, and it reaches an honest terminal status, protected by a lease protocol that a stale or duplicate worker can't break.

## User stories
- As a developer, I submit a run through the API and a real Chromium session executes the checkout suite against demo-shop.
- As an operator, if the worker dies mid-run, the target's state shows it clearly instead of claiming success.

## Interview signal
`FOR UPDATE SKIP LOCKED`, leases and fencing tokens, safe child-process management, parsing a machine-readable reporter, structured logging.

## Functional requirements
1. **FR-1 Manifest:** move the PRD-01 spec into `worker/suites/` and register it in `worker/manifest/suites.json` (contracts §10). Add a second suite, `catalog-regression` (10–15 cases), so the system handles more than one suite.
2. **FR-2 Service authentication:** every `/internal/v1/*` endpoint requires a shared service token (`TESTGRID_WORKER_TOKEN`, compared in constant time) on a separate internal port. The public port returns 404 for `/internal/*`.
3. **FR-3 Claim:** `POST /internal/v1/claims/next` picks the oldest eligible `QUEUED` target matching the worker's engines, using `FOR UPDATE SKIP LOCKED`.
   - It creates the attempt (`CLAIMED`) with a random 32-byte token, stored only as a sha256 hash, and `leased_until = now() + TTL` using the database clock.
   - The partial unique index guarantees one active attempt per target.
   - It responds with the claim payload (contracts §7).
4. **FR-4 Start, heartbeat and complete**
   - All three are conditional updates that check the attempt is active, the token matches and the lease hasn't expired.
   - Heartbeat extends the lease and returns `cancelRequested`.
   - Complete validates the case keys against the claim, inserts the case results, sets the terminal attempt and target status (derived as in contracts §2.2), recomputes the run and writes `run_events`, all in one transaction.
   - The same `completionKey` sent twice returns `200` with the original result. A different key after a terminal state returns `409`. A stale lease returns `410`.
5. **FR-5 Worker runtime** (`worker/src`)
   - A poll loop with backoff when there's no work, and a heartbeat timer.
   - It runs Playwright through `spawn(nodeBin, [playwrightCli, 'test', ...typedArgs], {shell: false})`. The arguments are built only from the manifest:
     - spec files
     - `--project=<engine>`
     - `--grep` from the claimed case tags
     - `--reporter=json`
     - an output directory in a temporary folder
   - Environment passed to Playwright: `TG_BASE_URL` (from the allowlisted `baseUrlRef`), `TG_FIXTURE_MODE`, `TG_FIXTURE_SEED`.
   - The JSON report is parsed with a zod schema and mapped to case results by tag. A `FLAKY` case is one that passed after a Playwright retry.
6. **FR-6 Timeouts and cleanup**
   - Attempt timeout comes from the manifest.
   - On timeout or cancel, send `SIGTERM` to the process group, then `SIGKILL` after 10 s, delete the temporary folder, and report `TIMED_OUT` or `CANCELLED`.
   - On the worker's own `SIGTERM`, stop claiming, give the current attempt the grace period, then report `INFRA_ERROR (WORKER_SHUTDOWN)`.
7. **FR-7 Failure classification at the source:** map Playwright errors to `ErrorClass`:
   - `expect` failures → `ASSERTION`
   - locator not found → `SELECTOR_NOT_FOUND`
   - test timeout → `TEST_TIMEOUT`
   - browser failed to launch → `BROWSER_LAUNCH`
   - process crash → `UNKNOWN`
8. **FR-8 Log correlation:** JSON logs in both the API and the worker carry `runId`, `targetId`, `attemptId`, `workerId` and `traceId`. Lease tokens and the service token are never logged; a test checks this.
9. **FR-9 `GET /runs/{id}/results`:** every attempt per target with its case results, newest attempt first.
10. **FR-10 Engines:** start with Chromium only. After AC-1 passes, enable Firefox and WebKit using the pinned Playwright Docker image, and document any platform differences.

## Tasks (in order)
- [ ] **T1** — Create worker package, manifest loader with schema validation, and two registered suites.
- [ ] **T2** — Implement service-token internal API: claims/next, start, heartbeat, complete with DB state transitions.
- [ ] **T3** — Implement safe Playwright spawning, JSON reporter parsing and case mapping by tag.
- [ ] **T4** — Write first happy-path run end to end and the PRODUCT_DEFECT failure path.
- [ ] **T5** — Add timeout/cancel/shutdown process-group cleanup and error-class mapping.
- [ ] **T6** — Add structured log correlation and token-redaction test.
- [ ] **T7** — Run on Chromium, Firefox and WebKit and document platform limitations.

## Acceptance criteria
- [ ] **AC-1:** An API submit leads to a real Chromium run that stores `PASSED` with 6 case results (E2E-001).
- [ ] **AC-2:** `PRODUCT_DEFECT` gives `FAILED` with `@cart-total` as `ASSERTION`. `SELECTOR_DRIFT` gives `SELECTOR_NOT_FOUND`. `TIMEOUT` gives `TIMED_OUT`. None of them is a false pass.
- [ ] **AC-3:** A stale lease gets `410`; a duplicate completion with the same key gets `200` and changes nothing; a different key gets `409` (Q-005 at single-worker level).
- [ ] **AC-4:** After `kill -9` on the worker mid-run, the target stays non-terminal with a visibly expired lease (`leased_until < now()`). It is not marked passed. Recovery itself waits for PRD-04.
- [ ] **AC-5:** Malformed worker data (an unknown case key, a negative duration) gets `422` and nothing is persisted.
- [ ] **AC-6:** All three engines run the suite; any engine-specific limitation is documented (E2E-002).

## Verification
- E2E: a happy run, then a wrong-total run.
- Unit tests: state transitions, manifest validation, reporter parsing (including truncated JSON), argument builder (rejects anything not in the manifest).
- Integration: duplicate completion, stale token, malformed payload, two concurrent `claims/next` calls (each target claimed only once).

## Demo moment
Run `PASS`, then `PRODUCT_DEFECT`, and show the case results. Kill the worker and show the expired lease: honest, not green.

## Risks
- A mismatch between the Playwright package and the browser image.
- Reporter format changes (pin the version and test the parser against a recorded sample).
- Zombie browser processes (use process groups).

## Out of scope
RabbitMQ, parallel workers, automatic recovery, cloud executors.

## Handoff and definition of done
- Implement **PRD-03 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T7 with tests, then write `docs/evidence/PRD-03.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain SKIP LOCKED and the lease check without AI;
  - the tracker is updated only after human review.

# Evidence — PRD-06

**PRD:** `prds/PRD-06-artifacts-and-timeline.md`  
**Branch / commits:** `prd-06/artifacts`, opened as https://github.com/samsaturn007-git/TestGrid/pull/9 and merged into `main` from it. ADR and rulings `a3dbfdd`; T1 `dd1a2d6`; T2 `0cdb969`, `28c2374`, `63027d3`, docs `5271dd4`; T3 `57394d9`, `d0aedce`, `ea41bb8`, drill `d08e3ef`, docs `4e0606c`; test fix `1ad2a47`; T4 `497c1c3`, docs `b23bdde`; T5 `29b64df`, `cdca634`, `35b9cf9`, docs `87863cf`; T6 `99a6a7c`, docs `f2bd96c`; tracker `3523914` (start), `3cd4b3a`, `7823ced`, `67fc201`, `0e2e529`, `e13a2e9`, `7b73d6e` (T1–T6 checked).  
**Reviewer:** developer (repository owner). On 2026-11-23, after Claude pushed the branch and opened PR #9, the developer asked Claude to do the remaining review steps and close PRD-06 on their behalf, as they did for PRD-03, PRD-04 and PRD-05: accept ADR-005 and rulings N20–N23, write the explain-back, run `tools/progress.py done` and merge PR #9 (see the last section).  
**Date:** 2026-11-23 (UTC)  
**Outcome:** pass. Every AC was verified locally against Testcontainers and the live Compose stack (below). GitHub Actions run 45230121570 on the PR #9 head `10c47c4` passed both required jobs ("check + unit tests" and the compose job with the E2E, the dashboard E2E and axe) on the first attempt, in 8 min 12 s.

Reference machine: Apple M4, 16 GiB, macOS 27.2, OrbStack (Docker 29.8.2, Compose 5.5.0), JDK 21.0.13.1, Node 26.13.1, pnpm 12.21.0, PostgreSQL 18.7, RabbitMQ 4.4.1, `@playwright/test` 1.65.1 (Chromium 159), axe-core 4.14.1, `@axe-core/playwright` 4.14.0. **No new dependency:** the HMAC is the JDK's `javax.crypto`, the ZIP scan uses Node's `zlib`, and the multipart reader is our own (`commons-fileupload2` was only at `2.0.0-M5`). `docs/VERSIONS.md` is unchanged. API tests are **live** against Testcontainers PostgreSQL and a real local store. Worker and web unit tests use fakes (see "Skipped or mocked tests"). Drills and E2E are **live** against the Compose stack with the real worker and demo shop. Only the fake values from `.env.example` were used. Host port 3000 was held by an unrelated Next.js dev server, so the live web checks ran with `WEB_PORT=3001`.

## Acceptance criteria with evidence

| Criterion | Pass? | Test ID(s) | Proof (file, test and observable behaviour) |
|---|---|---|---|
| AC-1 | Yes | UI-002 | Live `PRODUCT_DEFECT` run: the `cart-total` failure has a SCREENSHOT (1280×720 PNG showing Total $82.99 for $74.99 + $6.00 tax) and a TRACE (valid 38-entry Playwright ZIP), both linked by `case_result_id` to that case of that attempt, plus the attempt's LOG (`raw/prd-06-live-capture.txt`). The trace, downloaded through a signed link (`raw/prd-06-retrieval-proxy.txt`, served sha256 = stored sha256), opens in `playwright show-trace` at the failing step: `getByTestId('cart-total')`, Expected "$80.99", Received "$82.99" (**`raw/prd-06-ac1-trace-viewer.png`**, captured by `scripts/trace-viewer-shot.mjs`). Tests: `ArtifactUploadTest.storesEachKindAndTheCompletionLinksScreenshotsAndTracesToTheirCase`, worker `evidence.test.ts`, `report.test.ts` (evidence from the recorded 1.65.1 report). |
| AC-2 | Yes | ART-002 | API: `ArtifactRetrievalTest.art002AnExpiredArtifactIs410AndStillDescribed`, `…PastExpiresAtIsExpiredBeforeThePurgeJobRuns`, `…MissingBytesAre404WhetherMarkedOrNot` (EXPIRED/PURGED/past-expiry → `410 artifact-expired`, MISSING or bytes gone → `404`, and metadata still answers with the state). Retention: `ArtifactRetentionTest` (8) and `ArtifactRetentionLoopTest`. UI: `CaseDrawer.test.tsx` "art002: explains expired and missing files instead of showing a broken image". **Live:** with 1-minute retention the real loop moved a run's three artifacts to `PURGED` and deleted their files; a new link answered `410`; the drawer shows `PURGED` and "deleted after its retention period … The case result and error message are kept" (**`raw/prd-06-ac2-retention-live.txt`**, **`raw/prd-06-ac2-purged-drawer.png`**). |
| AC-3 | Yes | ART-001, ART-003 | Oversized: `ArtifactUploadTest.art001OversizedUploadsAre413AndLeaveNothing` (refused while streaming, and from `Content-Length` before reading), `ArtifactValidatingStreamTest.art001RefusesOneByteOverTheLimitForEachKind`. Wrong MIME: `…WrongBytesOrContentTypeAre415AndLeaveNothing` (ZIP as screenshot, PNG as trace, binary or empty log, JSON body). `../` in any ID: `LocalFsArtifactStoreTest.art003RejectsAnyKeyItCouldNotHaveGenerated` (11 forged keys) and `…Symlinked*`; `ArtifactUploadTest.art003CaseKeysAndQueryAreChecked` (`caseKey=..%2F..%2Fetc`, `path=` parameter, forged attempt ID); `ArtifactRetrievalTest.art003ForgedIdsAndExtraParametersReachNoFile`; DB CHECK on `storage_key` (`ArtifactSchemaTest`). Unsigned or expired link: `ArtifactRetrievalTest.art003UnsignedForgedMovedAndExpiredLinksAre403` (unsigned, no sig, forged, extended expiry, moved to another artifact, expired, longer than 300 s) and `ArtifactLinksTest`. No file outside the root, none partial: every rejecting test asserts the store root has no regular file, `.incoming` included. |
| AC-4 | Yes | SEC-002 (seed) | `make scan-canary` (**`raw/prd-06-final-live.txt`**, first run in `raw/prd-06-scan-canary.txt`): a fresh 34-character canary (below the 40-character opaque rule, so only the secret-value rule can catch it) goes into the worker's and API's environment and into every test page (cookie, DOM, test output). A `PRODUCT_DEFECT` run is scanned: API responses, the replayed event stream, every row of 7 tables, every stored artifact with traces unzipped, the api and worker logs. 865 files, canary absent. Positive controls prove it was planted: the stored log shows `[canary] [REDACTED]` and the failed case's trace was **withheld** because it held the canary cookie (N22). Unit: `redact.test.ts`, `zip-scan.test.ts`, `RedactorTest` (headers, cookies, JWTs, secret-named env values). |
| AC-5 | Yes | Q-004 | `make kill-check` on the final T5 build (**`raw/prd-06-ac5-kill-timeline.txt`**): `GET /runs/{id}/timeline` shows attempt 1 `ABANDONED · LEASE_EXPIRED` with one gap of 33 786 ms (killed before its first heartbeat, until the reaper's abandonment), and attempt 2 below it with heartbeats every ~10 s and no gap. The dashboard draws the hatched gap with "no heartbeat for 33.8 s (15:12:08 to 15:12:41)" (**`raw/prd-06-ac5-timeline.png`**). Tests: `RunTimelineApiTest.ac5AnAbandonedAttemptShowsItsHeartbeatGapAboveTheRetry`, `RunTimelineGapsTest`, `CaseDrawer.test.tsx` "shows the abandoned attempt above its retry". |

## Tasks completed

| Task | Commit(s) | Notes |
|---|---|---|
| T1 | `dd1a2d6` | `ArtifactStore` and `LocalFsArtifactStore`: server keys `yyyy/mm/dd/<uuid>.<ext>`, real-root containment, no link at any component, `NOFOLLOW_LINKS`, atomic rename from `.incoming`. Non-root `artifacts` volume. **The optional S3 adapter was skipped at the developer's request.** |
| T2 | `0cdb969`, `28c2374`, `63027d3` | V8 `artifacts` (immutable content, forward-only state, kind/MIME/size CHECKs); streaming single-part multipart reader; validator (limits, PNG/ZIP signature, UTF-8 without NUL); upload under the lease in two short transactions with no lock while streaming; completion links artifacts to case results (N20); the 64 KB cap skips only this path (N21). |
| T3 | `57394d9`, `d0aedce`, `ea41bb8`, `d08e3ef` | Redaction (headers, cookies, bearer, JWT, opaque strings, `*_TOKEN/_KEY/_SECRET/_PASSWORD` values) in worker and API; trace secret scan that fails closed (N22); 256 KB log tail; per-case screenshot and trace upload, path-checked; failures are `ARTIFACT_STORE` warnings; local-only canary in the page; `make scan-canary`. |
| T4 | `497c1c3` | `GET /artifacts/{id}`, `POST …/links` (HMAC-SHA256 over ID and expiry, database clock, ≤ 300 s), `GET …/content` (403 before any lookup, then 404/410; attachment, nosniff, sandbox CSP, no-store, CORP, no-referrer). |
| T5 | `29b64df`, `cdca634`, `35b9cf9` | V9 `attempt_heartbeats` and `case_results.start_offset_ms` (N23); `GET /runs/{id}/timeline` with gaps; worker start offsets from Playwright; Timeline section (lanes for parallel cases) and the case drawer (signed screenshot, trace download and command, log tail, state badges); real-browser axe. |
| T6 | `99a6a7c` | Hourly retention: expire → delete bytes → `PURGED`, orphan files after a 1 h grace, rows without files → `MISSING`, stale partial uploads; V10 index. |

## Changed files

- `api/src/main/java/dev/testgrid/api/artifact/*` (new): store, keys, kinds and states, multipart reader, validator, repository, upload view, link signer, read service and controller, retention service and loop, properties.
- `api/src/main/java/dev/testgrid/api/internal/ArtifactUploadController.java`, `ArtifactUploadService.java` (new); `AttemptService.java`, `AttemptRepository.java`, `WorkerApi.java`: lease check for uploads, heartbeat rows, `startOffsetMs`, artifact linking at completion.
- `api/src/main/java/dev/testgrid/api/run/RunTimeline.java`, `RunTimelineService.java` (new); `RunController.java`, `RunResults.java`, `RunRepository.java`, `RunQueryService.java`: timeline endpoint; artifacts and offsets in results.
- `api/src/main/java/dev/testgrid/api/common/Redactor.java`: FR-5 patterns and secret-named env values.
- `api/src/main/java/dev/testgrid/api/problem/ProblemType.java`, `RequestBodyLimitFilter.java`: `artifact-too-large`, `artifact-expired`, `forbidden`; the upload exemption.
- `api/src/main/resources/db/migration/V8__artifacts.sql`, `V9__timeline.sql`, `V10__artifact_purge.sql`; `application.properties`; `api/Dockerfile` (artifacts directory owned by uid 1000).
- `api/openapi.yaml`, `packages/contracts/src/api.ts`: regenerated.
- `worker/src/redact.ts`, `zip-scan.ts` (new), `evidence.ts` (new), `report.ts`, `attempt.ts`, `run-playwright.ts`, `api-client.ts`, `worker.ts`, `config.ts`, `main.ts`; `worker/suites/support/shop-test.ts` (drill-only canary).
- `web/src/runs/detail/Timeline.tsx`, `timelineModel.ts`, `CaseDrawer.tsx` (new); `BrowserMatrix.tsx`, `RunDetailPage.tsx`; `web/src/api/queries.ts`, `types.ts`; tests and `web/e2e/a11y.spec.ts`.
- `infra/compose/compose.yaml`, `.env.example`: artifacts volume, link key, retention, canary.
- `scripts/scan-canary.sh`, `scripts/trace-viewer-shot.mjs`, `scripts/prd06-dashboard-shots.mjs`, `Makefile` (`scan-canary`).
- Docs: `docs/decisions/ADR-005-artifact-storage.md` (Proposed), `docs/decisions/README.md`, `docs/API_AND_DATA_CONTRACTS.md` (§3, §4, §5, §6 types, §7), `docs/CONFLICT_REGISTER.md` (N20–N23), `docs/ARCHITECTURE.md` §6, `docs/RUNBOOK.md` §6a and §7.

## Actual executed commands and outputs

```text
$ make check
… prettier --check / eslint --max-warnings=0 / tsc (demo-shop, worker, web, contracts) / ./mvnw spotless:check compile
exit 0

$ make test
demo-shop test: ℹ tests 42   ℹ fail 0
worker test:    ℹ tests 102  ℹ fail 0
web test:       Test Files 15 passed (15)   Tests 245 passed (245)
api (surefire): 69 classes, tests=468, failures=0, errors=0, skipped=0
exit 0
(baseline on main before PRD-06: api 373, worker 82, web 235, demo-shop 42)

$ make contracts-check
contracts-check OK: api/openapi.yaml and packages/contracts/src/api.ts match the code

$ bash tools/vocab_check.sh
Non-canonical spellings: none
vocab_check OK

$ python3 tools/progress.py lint
lint OK: tracker matches PRD files

$ bash scripts/scan-canary.sh          (raw/prd-06-final-live.txt)
  ok   a log tail was stored for the failed attempt
  ok   the log tail shows the masked canary line
  ok   the failed case's screenshot was stored
  ok   the failed case's trace was withheld because it held the canary
--- scanning 865 files for the canary
  ok   canary absent from API responses, events, database rows, stored artifacts and logs
scan-canary: PASS

$ make e2e                             (raw/prd-06-final-live.txt)
ok   checkout-smoke PASS → PASSED / PRODUCT_DEFECT → FAILED / SELECTOR_DRIFT → FAILED / TIMEOUT → TIMED_OUT
ok   catalog-regression PASS → PASSED
e2e OK

$ (cd web && E2E_BASE_URL=http://127.0.0.1:3001 pnpm exec playwright test)
  20 passed (2.1m)       (16 axe checks incl. the PRD-06 drawer and timeline in both themes, 4 PRD-05 flows)

$ make kill-check                      (raw/prd-06-ac5-kill-timeline.txt)
attempt 2 claimed 32 s after the kill … kill-check OK
```

Artifact counts per live scenario (`raw/prd-06-live-capture.txt`): PASS 0/0/0, PRODUCT_DEFECT 1 log / 1 screenshot / 1 trace, SELECTOR_DRIFT 1/2/2, TIMEOUT 1/2/2. Index checks: `ArtifactSchemaTest.lookupsByAttemptAndThePurgeScanUseTheirIndexes` (`EXPLAIN` shows `artifacts_attempt` and `artifacts_expiring`).

## Negative / failure tests

- **Upload cut off mid-stream, validator exception:** `LocalFsArtifactStoreTest.art001AFailingStreamLeavesNoFileBehind`, `…ValidatorRuntimeExceptionAlsoLeavesNoFile`: no file, not even in `.incoming`.
- **Lease lost while streaming:** `ArtifactUploadTest.aLeaseLostWhileStreamingDeletesTheStoredBytes`: the lease is expired by SQL mid-upload; the second check refuses with `410` and the stored bytes are deleted.
- **Zombie and late uploads:** wrong token `410`, expired lease `410`, finished attempt `409`, unknown attempt `404`; nothing stored.
- **Quota:** the 21st artifact of an attempt is `413`; 20 files and rows remain.
- **Upload failures in the worker:** `evidence.test.ts`: a failed upload is an `ARTIFACT_STORE` warning and the rest continue; `410` stops the rest; the completion is unchanged. Unreadable, linked, empty or oversized files are skipped.
- **Trace scan fails closed:** `zip-scan.test.ts`: unknown method, truncated, ZIP64 marker, not a ZIP, and a 2 MB zip bomb against a 1 MB budget are all withheld.
- **Purge delete fails:** `ArtifactRetentionTest.aFailedDeleteLeavesTheRowExpiredAndTheNextPassFinishesIt`: a non-empty directory where the file was makes the delete really fail; the row stays `EXPIRED` (reads `410`) and the next pass finishes it.
- **Crash between expire and purge:** `…aCrashBetweenExpiringAndPurgingIsFinishedByTheNextPass`.
- **Uploads in flight never look like orphans:** `…theSweeperDeletesOldOrphanFilesButNotYoungOnesOrFilesWithRows`.
- **Mutation checks (broken on purpose, test failed, restored):**
  - The missing-rows keyset scan started at `new UUID(Long.MIN_VALUE, …)`, so it skipped every ID starting `0`–`7` (PostgreSQL orders UUIDs as unsigned bytes). `ArtifactRetentionTest` caught it intermittently. The fixed-ID regression test fails deterministically with the old value: expected 2, got 1 (`raw/prd-06-ac2-retention-live.txt`).
  - The real-browser axe run caught WCAG 2.5.3 "label in name" on the new matrix buttons, which also failed the PRD-05 run-page check. Fixed; 16/16.
- **A timing flake found and fixed:** two PRD-03 process-group tests failed once under a loaded `make test`, because their 200–300 ms timers fired before the fake Playwright CLI had started. They pass 5/5 alone. The timers were lengthened and the assertions unchanged (`1ad2a47`).

## Security and threat check

- **Input restrictions:**
  - The worker names only a kind (enum) and a case key (checked against the claim).
  - The server chooses the key, path, extension and MIME type.
  - Limits apply per kind and per attempt (20 artifacts, 200 MB), along with magic bytes and strict query parameters.
  - Only the upload path skips the 64 KB cap, and only behind the internal port's service token.
- **Path traversal and links:**
  - Keys must match the server's own pattern; the DB enforces it too.
  - Resolution stays under the real root, and links are refused at every component and opened with `NOFOLLOW_LINKS`.
  - Report paths from the Playwright child are re-checked in the worker.
- **Unauthorised artifact read:**
  - Content is readable only through an HMAC-signed link that lasts at most 5 minutes. The signature binds the ID and expiry and is checked in constant time, before any lookup.
  - Responses are attachments with nosniff, a sandbox CSP, no-store, same-origin CORP and no referrer.
  - **Not access control:** until PRD-12, anyone who can reach the public port can ask for a link (ADR-005 says so).
- **Secrets:**
  - Two layers of redaction: the worker, then the API.
  - The worker's own secrets never reach Playwright (allow-listed child environment).
  - A trace holding a known secret is withheld.
  - Screenshots are not scanned (pixels); this is a limitation.
  - Canary scan: pass.
  - New secret: `TESTGRID_ARTIFACT_LINK_KEY`, a fake local value in `.env.example`. Its name ends in `_KEY`, so its value is masked too.
- **Resource exhaustion:**
  - Streaming with bounded memory: a 64 KB window, and no DB lock while bytes flow.
  - Per-attempt quota, retention, the orphan sweep, and a 256 MB inflate budget for the trace scan.
- **New attack surface:** the internal upload endpoint (service token plus lease); three public read endpoints (scoped, signed); a writable volume in the API container (uid 1000, mode 0700, internal only).

## Skipped or mocked tests

- **Optional S3 adapter:** not built (developer's decision, 2026-11-23). No `s3` Compose profile.
- **Worker unit tests:** use a fake API (`uploadArtifact` stub), a fake HTTP server for the wire format, hand-built ZIPs and a recorded Playwright 1.65.1 report. The real path ran live in every drill.
- **Web component tests** (Vitest, 245) use the fake API; their axe runs with colour contrast off (jsdom). The real-browser axe E2E has contrast on.
- **Browsers:** dashboard E2E and live runs used Chromium only; Firefox and WebKit artifacts were not run.
- **Trace viewer:** the in-app browser pane would not register the viewer's service worker, so AC-1's screenshot was taken with the pinned Playwright Chromium (headless).
- **CI:** run 45230121570 on PR #9 (head `10c47c4`) passed both jobs on the first attempt. The close commit re-runs it before the merge.

## Screenshots / demo / raw data links

- `docs/evidence/raw/prd-06-ac1-trace-viewer.png`: the trace viewer at the failing `cart-total` assertion.
- `docs/evidence/raw/prd-06-case-drawer.png`: the drawer for a TIMED_OUT case (screenshot, trace command, log tail).
- `docs/evidence/raw/prd-06-ac2-purged-drawer.png`, `prd-06-ac2-retention-live.txt`: the live purge and sweep, plus the mutation check.
- `docs/evidence/raw/prd-06-ac5-timeline.png`, `prd-06-ac5-kill-timeline.txt`: worker kill → abandoned attempt with gap.
- `docs/evidence/raw/prd-06-live-capture.txt`, `prd-06-retrieval-proxy.txt`, `prd-06-scan-canary.txt`, `prd-06-final-live.txt`.
- Demo moment: open the failed `cart-total` cell → the screenshot shows the wrong total → Download trace → `npx playwright show-trace trace-<id>.zip` → step to the failing assertion.

## Known limitations and deferred improvements

- **No access control on links:** `POST /artifacts/{id}/links` is open to anyone who reaches the public port until PRD-12.
- **Screenshots are not scanned for secrets.** A secret drawn on the page would be stored.
- **Withheld traces:** a trace containing any known secret value is withheld entirely, so that failure has no trace.
- **Log masking is broad:** the 40-character opaque rule also masks Playwright's long per-test folder names in the stored log.
- **Warnings only in the log:** the `ARTIFACT_STORE` warning is a worker log line; the completion has no warnings field.
- **Approximate alignment:** case positions are exact relative to each other (Playwright's clock), but the first case is aligned to the attempt's `started_at`, which is a little before Playwright really starts.
- **Single API instance:**
  - The sweeper lists the whole store each hour (O(files)).
  - Two API instances would both run it: safe (idempotent), but wasteful.
  - Link keys are per deployment; with no key set, a random one is used and links end at a restart.
- **Design deviation:** the run page has a Timeline *section*, not the design's tabs (N23). Tabs can follow without an API change.
- **Duplicate headers:** nginx repeats `X-Content-Type-Options` and `Referrer-Policy` on content responses (same values, harmless).
- **Watch item:** in one run a PRD-05 run-list test ("load older runs") failed alongside unrelated failures and then passed on every later run. Not changed.

## Rulings (accepted 2026-11-23 at the developer's request)

- **ADR-005:** server-keyed store behind an interface, uploads under the lease, content only through signed links. Status changed from Proposed to **Accepted**.
- **N20:** upload before `complete`; the completion links artifacts to case results.
- **N21:** only the upload path skips the 64 KB body cap.
- **N22:** a trace holding a secret is withheld, not redacted; screenshots are not scanned.
- **N23:** `attempt_heartbeats`, `case_results.start_offset_ms`, and a Timeline section instead of tabs.

All four are marked Closed in `docs/CONFLICT_REGISTER.md`. They were accepted as proposed; the developer did not change any of them.

## Explain-back review

> **Written by Claude at the developer's request, not in the developer's own words.** On 2026-11-23 the developer asked Claude to close PRD-06 on their behalf, as they did for PRD-03, PRD-04 and PRD-05. PRD-06's definition of done says "the developer can explain the traversal and redaction defences without AI". These answers don't show that, so that item is **still owed**: the developer should answer the quiz below without notes and replace these answers.

### Quiz (answer without notes)

1. The three most important engineering decisions in this PRD, and why.
2. What happens if the main operation fails halfway through? For example, the API dies after the file is moved into place but before the row is inserted, or the worker loses its lease while a trace is uploading.
3. What would you change for production?
4. **Traversal:** name every check between `GET /artifacts/{id}/content?…` and the bytes on disk. Which one stops `../../etc/passwd`, which stops a symlink planted inside the store, and why isn't the key regex alone enough?
5. **Redaction:** the canary is 34 characters. Which rule masks it in the log tail, why is it deliberately shorter than 40, and why is the trace withheld rather than redacted?
6. **Signed links:** what exactly is signed, why is the signature checked before the database lookup, and what does the link *not* protect against until PRD-12?
7. **Retention:** why are the bytes deleted before the row is marked `PURGED`, and why does the orphan sweeper ignore files younger than an hour?
8. **The UUID bug:** why did a keyset scan starting at `new UUID(Long.MIN_VALUE, Long.MIN_VALUE)` skip about half the rows in PostgreSQL?

### Claude's answers (on the developer's behalf)

1. **Three decisions.**
   - **No client or worker value ever becomes a path.** The server generates every storage key from the kind and the date, stores it in the row, and the public API takes only the artifact's UUID. Everything else (extension, MIME type, size limit) follows from the kind. This removes traversal as a class rather than filtering for it, and the store still checks every key and path as a second line (ADR-005).
   - **Uploads ride on the lease, before `complete` (N20).** The worker already holds a token that proves it owns the attempt, so the upload uses the same check as start and heartbeat; a zombie worker gets `410`. Uploading before completion means the completion transaction can link each artifact to its case result atomically, and a target never looks finished while its evidence is still arriving.
   - **Fail safe on secrets and storage.** Redaction runs twice (worker, then API). A trace is scanned for every known secret and withheld if it holds one or can't be read completely (N22). A failed upload never changes a test result; it is only an `ARTIFACT_STORE` warning. Retention deletes bytes before saying so, and never deletes rows.
2. **Failing halfway.**
   - **API dies after the file is moved into place, before the row is inserted:** the file is under the root with no row, so nothing can read it (every read goes through a row). The worker's upload call fails; it logs `ARTIFACT_STORE` and still completes the attempt. An hour later the orphan sweeper sees a file with no row, older than the grace, and deletes it.
   - **API dies mid-stream:** the bytes are in `.incoming/<uuid>.part`, never visible under a key. The sweeper removes partial files older than the grace.
   - **Lease lost while a trace uploads:** no database lock is held while bytes stream. The second transaction locks the attempt again, sees the lease expired, answers `410 lease-expired`, and the service deletes the bytes it just stored. The worker stops uploading the rest and doesn't complete; the reaper owns recovery.
   - **Purge dies between deleting bytes and marking the row:** the row stays `EXPIRED` (readers already get `410`), and the next pass deletes again (a no-op) and marks it `PURGED`.
3. **For production.**
   - Real access control on `POST …/links` and the metadata endpoints: user tokens and scopes (PRD-12). Today the signature only limits how long a leaked URL works.
   - Object storage (the S3 adapter) with server-side encryption, a lifecycle rule as a backstop to the purge job, and the store's listing replaced by a paginated, prefix-based sweep, not a full walk every hour.
   - The link key in a secret manager, with rotation (accept the previous key for 5 minutes).
   - Screenshot policy: block or mask sensitive regions in Playwright (`mask:`), since pixels can't be scanned for text.
   - A warnings field on the completion (or a run event) so an `ARTIFACT_STORE` warning is visible in the UI, not only in the worker log.
   - Metrics for upload failures, withheld traces, purge failures and store size, with alerts.
4. **Traversal.** In order:
   1. The path variable must parse as a UUID (Spring, `400` otherwise), so `..%2F..%2Fetc%2Fpasswd` never reaches our code.
   2. The query may only hold `expires` and `sig` (`400`), and the HMAC must verify (`403`) before anything else runs.
   3. The row is looked up by that UUID in the caller's project (`404`), and its state must be readable (`404`/`410`).
   4. The storage key comes from the row, not the request, and must match `ArtifactKeys.PATTERN` (`yyyy/mm/dd/<uuid>.(png|zip|log)`). The database has the same CHECK.
   5. `root.resolve(key).normalize()` must still start with the real root.
   6. Every existing component from the root to the file must not be a symlink, the file must be a regular file, and it is opened with `NOFOLLOW_LINKS`.

   `../../etc/passwd` is stopped first by the UUID path variable, and in the store by the key pattern and the "stays under the root" check. A symlink planted inside the store, for example `2026/11/23` pointing at `/etc`, has a perfectly valid key, so the regex can't see it: the per-component link check and `NOFOLLOW_LINKS` stop it. That is why the regex alone isn't enough: it validates the string, not what the filesystem does with it. Writes get the same checks, and directories are created one level at a time, refusing any existing link or non-directory.
5. **Redaction.** The canary's value is in the worker's environment as `TESTGRID_CANARY_SECRET`. That name ends in `_SECRET`, so `secretsFromEnv` collects its value and the exact-value rule replaces every occurrence with `[REDACTED]` before any pattern runs. It is 34 characters on purpose: the "long opaque string" rule masks 40+ characters of `[A-Za-z0-9_-]`, so a 40-character canary would be masked even if the secret-value rule were broken, and the drill would prove nothing. The trace is withheld because it is a ZIP of JSON and resources (DOM snapshots, the network log with the `Cookie` header). Rewriting text inside it means changing Playwright's internal format, and a missed encoding (base64, escaped JSON) would leak silently. Scanning for the secret's bytes and withholding is simple and safe. The scan fails closed: an unreadable ZIP is withheld too.
6. **Signed links.** `sig = base64url(HMAC-SHA256(TESTGRID_ARTIFACT_LINK_KEY, "v1:<artifactId>:<expires>"))`. Binding the artifact ID means a signature can't be moved to another artifact; binding `expires` means it can't be extended; `v1` allows a future format change. The API also refuses an `expires` more than 300 s ahead and compares in constant time. It is checked before the lookup so that a caller without a valid link gets the same `403` for every ID: they can't learn which artifacts exist, or which have expired, by probing. Until PRD-12 it does *not* stop anyone who can reach the public port from asking for a link: `POST …/links` has no authentication. It limits how long a leaked URL works; it is not access control.
7. **Retention.** Bytes first, then `PURGED`, so the row never claims something that isn't true. If the order were reversed and the delete failed or the API died, a row would say `PURGED` while the file stayed on disk forever (nothing ever re-checks `PURGED` rows). In this order, a failure leaves an `EXPIRED` row that the next pass retries, and readers get `410` meanwhile. The sweeper ignores young files because an upload moves its file into place and only then inserts the row, in a separate transaction. For that moment a perfectly good upload looks like an orphan; deleting it would lose evidence and leave a row pointing at nothing. One hour is far longer than any upload (60 s worker timeout), and the setting refuses less than 5 minutes.
8. **The UUID bug.** Java compares UUIDs as two signed 64-bit numbers, so `new UUID(Long.MIN_VALUE, Long.MIN_VALUE)` (`80000000-0000-0000-8000-000000000000`) is the smallest UUID in Java's order. PostgreSQL compares `uuid` values as 16 unsigned bytes, where `8…` is in the middle. A scan for `id > '80000000-…'` therefore skipped every id starting with `0`–`7`, about half of random UUIDs. The fix starts at the all-zero UUID, which is first in PostgreSQL's order. The tests caught it intermittently, and a fixed-id regression test now catches it deterministically (mutation check recorded).

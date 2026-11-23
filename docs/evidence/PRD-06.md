# Evidence — PRD-06

**PRD:** `prds/PRD-06-artifacts-and-timeline.md`  
**Branch / commits:** `prd-06/artifacts`, not pushed yet. ADR and rulings `a3dbfdd`; T1 `dd1a2d6`; T2 `0cdb969`, `28c2374`, `63027d3`, docs `5271dd4`; T3 `57394d9`, `d0aedce`, `ea41bb8`, drill `d08e3ef`, docs `4e0606c`; test fix `1ad2a47`; T4 `497c1c3`, docs `b23bdde`; T5 `29b64df`, `cdca634`, `35b9cf9`, docs `87863cf`; T6 `99a6a7c`, docs `f2bd96c`; tracker `3523914` (start), `3cd4b3a`, `7823ced`, `67fc201`, `0e2e529`, `e13a2e9`, `7b73d6e` (T1–T6 checked).  
**Reviewer:** [developer: review this file, answer the explain-back quiz in your own words, and rule on N20–N23 and ADR-005]  
**Date:** 2026-11-23 (UTC)  
**Outcome:** [pass, once the developer has reviewed it. Every AC was verified locally against Testcontainers and the live Compose stack. GitHub Actions has not run on this branch yet: it isn't pushed.]

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
- **CI:** GitHub Actions has not run: the branch is not pushed.

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

## Rulings awaiting the developer

- **ADR-005 (Proposed):** server-keyed store behind an interface, uploads under the lease, content only through signed links.
- **N20:** upload before `complete`; the completion links artifacts to case results.
- **N21:** only the upload path skips the 64 KB body cap.
- **N22:** a trace holding a secret is withheld, not redacted; screenshots are not scanned.
- **N23:** `attempt_heartbeats`, `case_results.start_offset_ms`, and a Timeline section instead of tabs.

## Explain-back review (developer's own words)

PRD-06's definition of done says "the developer can explain the traversal and redaction defences without AI". Answer these without notes and replace the brackets.

1. **The three most important engineering decisions in this PRD, and why:** [developer]
2. **What happens if the main operation fails halfway through?** For example, the API dies after the file is moved into place but before the row is inserted, or the worker loses its lease while a trace is uploading. [developer]
3. **What would you change for production?** [developer]
4. **Traversal:** name every check between `GET /artifacts/{id}/content?…` and the bytes on disk, and say which one stops `../../etc/passwd`, which stops a symlink planted inside the store, and why the key regex alone isn't enough. [developer]
5. **Redaction:** the canary is 34 characters. Which rule masks it in the log tail, why is it deliberately shorter than 40, and why is the trace withheld rather than redacted? [developer]
6. **Signed links:** what exactly is signed, why is the signature checked before the database lookup, and what does the link *not* protect against until PRD-12? [developer]
7. **Retention:** why are the bytes deleted before the row is marked `PURGED`, and why does the orphan sweeper ignore files younger than an hour? [developer]
8. **The UUID bug:** why did a keyset scan starting at `new UUID(Long.MIN_VALUE, Long.MIN_VALUE)` skip about half the rows in PostgreSQL? [developer]

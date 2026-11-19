# PRD-06 — Screenshots, Playwright traces, logs, timeline and secure artifact retrieval

**Priority:** P0 | **Estimated effort:** 12–18 h | **Dependencies:** PRD-05 | **Gate:** G2 | **Status:** not started

## Objective
Turn every failed attempt into a diagnosable record: screenshot, trace and a bounded, redacted log, all safely stored, expirable and shown on a timeline of exactly what happened and when.

## User stories
- As a developer, I open a failed case and see its screenshot, download its trace and read the redacted log tail for that exact attempt.
- As a developer, I see a timeline of an attempt (claimed, started, each case, artifacts, heartbeats, finished) with gaps highlighted.
- As an operator, artifacts expire automatically and an expired one shows a clear message instead of a broken page.

## Interview signal
Storage abstraction, streaming uploads, MIME sniffing, path-traversal defence, signed URLs, retention jobs, secret redaction.

## Functional requirements
1. **FR-1 Capture** (worker)
   - Playwright config: `screenshot: 'only-on-failure'`, `trace: 'retain-on-failure'`.
   - A worker log tail capped at 256 KB.
   - Artifacts are uploaded through the internal API after the case results are reported and before the attempt completes. An upload failure doesn't change the test result; it records `ARTIFACT_STORE` as a warning.
2. **FR-2 `ArtifactStore` interface:** `put(stream, meta) → key`, `open(key)`, `delete(key)`, `exists(key)`.
   - `LocalFsArtifactStore` uses server-generated keys `yyyy/mm/dd/<uuid>.<ext>`, refuses symlinks and checks the resolved path stays under the root.
   - The optional `S3ArtifactStore` is tested against an S3-compatible container (Compose profile `s3`; check the image licence before choosing).
3. **FR-3 Upload validation**
   - Streaming multipart with size limits per kind: screenshot 5 MB, trace 50 MB, log 1 MB.
   - Magic-byte sniffing: PNG signature for screenshots, ZIP signature for traces, UTF-8 text for logs.
   - sha256 computed while streaming; `413` or `415` on violation; no partial file left behind.
4. **FR-4 Retrieval**
   - `GET /artifacts/{id}/content` streams the file with `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`, and a strict CSP for inline images.
   - `POST /artifacts/{id}/links` issues an HMAC-signed link valid for 5 minutes or less (for `<img src>`).
   - `404` if missing, `410` if expired.
5. **FR-5 Redaction:** the worker redacts before upload, and the API redacts again before storing error text.
   - Patterns: `Authorization` and `Cookie` headers, bearer tokens, JWT-shaped strings, the values of any environment variable whose name matches `*_TOKEN|*_KEY|*_SECRET|*_PASSWORD`, and a canary secret.
6. **FR-6 Retention:** a purge job runs hourly.
   - Expired artifacts become `EXPIRED`, then `PURGED` once the bytes are deleted.
   - An orphan sweeper reconciles files that have no database row and rows that have no file (the latter become `MISSING`).
7. **FR-7 Timeline:** `GET /runs/{id}/timeline` returns ordered events grouped by target and attempt.
   - The UI draws a horizontal timeline per attempt, with case bars showing duration, artifact markers, and any heartbeat gap longer than 2 × the heartbeat interval highlighted.
8. **FR-8 Failure detail UI:** a case drawer with the error class, redacted message, screenshot (via signed link), trace download and `npx playwright show-trace <file>` instructions, log tail, and artifact state badges.

## Tasks (in order)
- [ ] **T1** — Create ArtifactStore interface, local implementation with traversal/symlink defences, optional S3 adapter.
- [ ] **T2** — Add artifact migrations and bounded streaming upload with MIME sniffing and sha256.
- [ ] **T3** — Capture screenshot/trace/log bundle in the worker with redaction and canary secret.
- [ ] **T4** — Expose content and signed-link endpoints with 404/410 semantics.
- [ ] **T5** — Build timeline API and UI plus failure detail drawer with artifact states.
- [ ] **T6** — Implement purge and orphan-sweeper jobs; write redaction, expiry and failure-handling tests.

## Acceptance criteria
- [ ] **AC-1:** A seeded `PRODUCT_DEFECT` failure has a screenshot and trace tied to its attempt and case; the trace opens in the Playwright trace viewer (evidence: screenshot of the viewer).
- [ ] **AC-2:** An expired artifact returns `410`, a missing one `404`, and the run page still renders with an explanatory state (ART-002).
- [ ] **AC-3:** An oversized upload, a wrong MIME type, `../` in any ID, and an unsigned or expired link are all rejected, and no file is created outside the root (ART-001, ART-003).
- [ ] **AC-4:** A canary secret put into the worker environment and the page is absent from every stored log, error text and API response, checked by an automated scan (SEC-002 seed).
- [ ] **AC-5:** The timeline shows a heartbeat gap and an `ABANDONED` attempt for a worker-kill run.

## Verification
- Integration tests for upload, download, expiry and the sweeper.
- A path-traversal and forged-ID test suite.
- The canary scan script: `scripts/scan-canary.sh`.

## Demo moment
Click the failed `@cart-total` cell → screenshot of the wrong total → open the trace → step to the failing assertion.

## Risks
- Traces containing secrets (the demo shop has none, the canary test proves it).
- Unbounded storage.
- Orphaned files.

## Out of scope
Video recording, a public CDN, embedding the trace viewer (a stretch goal only if the pinned Playwright version supports self-hosting it).

## Handoff and definition of done
- Implement **PRD-06 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T6 with tests, then write `docs/evidence/PRD-06.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain the traversal and redaction defences without AI;
  - the tracker is updated only after human review.

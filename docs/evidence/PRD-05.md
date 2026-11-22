# Evidence — PRD-05

**PRD:** `prds/PRD-05-live-dashboard.md`  
**Branch / commits:** `prd-05/live-dashboard` (local, not pushed) — handoff `9e80644`, format `d445ac2`, start `7cb5a39`; T1 `72329cc`, `99de2e6`; T2 `ffce1d2`, `82c36ee`; T3 `9ac7953`, `26e51ca`, test fix `92d104a`; T4 `b66b726`, `2b69e6a`; T5 `cd824de`, `16baa2a`; T6 `d293e82`, `0c87635`; T7 `9a6eb47`; tracker ticks `31792f3`, `1eebf8b`, `19c5e07`, `c3be075`, `faec4b7`, `2c0768c`, `b512544`. No PR yet.  
**Reviewer:** developer (repository owner), review pending. Claude wrote this file; it has not run `tools/progress.py done`.  
**Date:** 2026-11-22 (UTC)  
**Outcome:** pass (all five ACs verified locally against Testcontainers and the live Compose stack). GitHub Actions has **not** run on this branch yet, because it has not been pushed; CI now includes the dashboard E2E.

Reference machine: Apple M4, 16 GiB, macOS 27.2, OrbStack, JDK 21.0.13.1, Node 26.13.1, pnpm 12.21.0, PostgreSQL 18.7, RabbitMQ 4.4.1, React 19.3.1, Vite 8.3.8, Vitest 5.1.1, `@playwright/test` 1.65.1 (Chromium 159), axe-core 4.14.1, `@axe-core/playwright` 4.14.0. API tests are **live** against Testcontainers PostgreSQL. Web component tests use a **fake API** (see "Skipped or mocked tests"). The Playwright E2E is **live** against the Compose stack with the real worker and demo shop. Only the fake values from `.env.example` were used.

## Acceptance criteria with evidence

| Criterion | Pass? | Test ID(s) | Proof (file, test and observable behaviour) |
|---|---|---|---|
| AC-1 | Yes | UI-002 | `web/e2e/dashboard.spec.ts` "UI-002": the run is started from the New run dialog, the page opens on `/runs/:id`, shows `Live`, and the header badge reaches `Run status: PASSED` (PASS) and then `FAILED` (PRODUCT_DEFECT) **without a reload**; the API's `GET /runs/{id}` status is compared and equal. No database edits anywhere in the flow. Live checks in T5: a three-engine run's summary moved from `1 RUNNING, 2 QUEUED` to `1 FAILED, 1 RUNNING, 1 QUEUED. 11 of 12 case results PASSED` from stream events alone (network log: one `/events`, snapshot fetches only after events, no 5 s polling). |
| AC-2 | Yes | — | `web/src/components/StatusBadge.test.tsx` (45 tests): every value of `RunStatus`, `TargetStatus`, `AttemptStatus`, `CaseStatus` and `WorkerState`, written out by hand from contracts §2.1, renders its exact enum label, an icon and `aria-label` "<Kind> status: <VALUE>"; the five outcomes `PASSED, FAILED, TIMED_OUT, INFRA_ERROR, CANCELLED` have distinct icons; `QUEUED`/`CLAIMED`/`RUNNING` are distinct in shape (CLAIMED dashed) and from the outcomes; `INFRA_ERROR` is never the danger colour. The status tables are typed `Record<Enum, …>` over the generated contract types: deleting `CLAIMED` from `tokens.json` failed the build (TS2741). |
| AC-3 | Yes | UI-001 | `web/e2e/dashboard.spec.ts` "UI-001 / AC-3" (live, 55 s): new stream requests are blocked with `page.route(...).abort()` and the open stream is cut by restarting the web proxy. The page shows `Live updates paused, refreshing every 5s`, its header matches the API while polling, goes `Live` again within 45 s after `unroute`, and after the run ends its header and every engine column equal the API's final state with **no `.tg-pulse` left** (no stale RUNNING). **The test can fail:** with the fallback disabled in a rebuilt web image it failed at the banner (`raw/prd-05-dashboard-e2e.txt` holds the passing run). Server side: `RunEventStreamTest` (9) and `RunEventStreamSweepTest` (2) over real HTTP: replay after `Last-Event-ID`, one `RESYNC` past the limit, id order, rolled-back events never sent, delivery by NOTIFY within 3 s with a 60 s ping, and by the ping sweep with LISTEN off. Client side: `LiveUpdates.test.tsx` (14). Manual: stopping the API mid-run gave the banner within 15 s, then "Could not refresh… loaded at …" and no pulse; after restart the page settled on `PASSED, 36 of 36`. |
| AC-4 | Yes | — | **axe:** `web/e2e/a11y.spec.ts`, 14 checks in a real browser: Overview, run list with filters, run detail with case results open, suites expanded, the new-run dialog (clean and with field errors), Workers, not found, each in dark and light, WCAG 2.0/2.1/2.2 A and AA tags **including colour contrast**: no serious or critical violation. A throwaway self-check showed the run is real: colour-contrast evaluated 73 nodes on Workers, an injected `#dddddd` paragraph was reported `color-contrast: serious`, an unnamed button `button-name: critical`. Component-level axe (contrast off, jsdom has no layout) runs in every page test. **Keyboard:** "AC-4: keyboard only" in `dashboard.spec.ts` uses only `Tab` and `Enter`: New run → Start run → focus lands on `<main>` of the run page → Cancel run → focus on "Yes, cancel run" → Enter → `Cancel requested at …` → ends `CANCELLED` (API agrees). |
| AC-5 | Yes | UI-003 | `web/e2e/dashboard.spec.ts` "UI-003 / AC-5" (live): a three-engine PRODUCT_DEFECT run is cancelled once its first target has FAILED, so it ends with FAILED and CANCELLED targets; "Rerun failed targets (…)" opens a new run with `trigger RERUN`, `parentRunId` = the old run, and **exactly the failed engines**; the new page links back and the old page links forward. API: `RerunApiTest` (10): FAILED/TIMED_OUT/INFRA_ERROR are rerun, PASSED and CANCELLED never; replay with the same key; 409 for a reused key; `422 invalid-rerun` for an unfinished run or nothing failed; `422 unknown-suite` when the revision is no longer runnable. Live in T4: rerunning a run with `chromium CANCELLED, firefox FAILED, webkit CANCELLED` made a run with only `local:firefox:0`. |

## Tasks completed

| Task | Commit(s) | Notes |
|---|---|---|
| T1 | `72329cc`, `99de2e6` | React 19 + react-router data router + TanStack Query; Tailwind 4 `@theme inline` reads `docs/design/tokens.css` once (Tailwind's own palette cleared); skip link, landmarks, one focus ring, focus to `<main>` on navigation; `StatusBadge` from `tokens.json`. |
| T2 | `ffce1d2`, `82c36ee` | Suites page; native `<dialog>` new-run form: suite, engines from `allowedEngines`, fixture and seed only when `GET /capabilities` (new, N18) allows, branch and SHA; no URL/script/regex/flag fields. One idempotency key per distinct request body. |
| T3 | `9ac7953`, `26e51ca`, `92d104a` | `GET /runs` filters with V5 indexes; unknown parameters rejected; run list with filters in the URL, keyset Load more, empty/error states. |
| T4 | `b66b726`, `2b69e6a` | `POST /runs/{id}/rerun`, `invalid-rerun` problem type, `parentRunId`/`rerunIds`, V6 index; run page with matrix, attempts, sortable case table, cancel with confirmation, rerun. |
| T5 | `cd824de`, `16baa2a` | `GET /runs/{id}/events` (SseEmitter, V7 NOTIFY trigger, LISTEN thread, 15 s ping sweep, cap 100, 429); `useRunEvents` with coalesced refetch, id ordering, RESYNC, fallback to 5 s polling with the banner, 10 s reconnect watchdog; nginx `proxy_connect_timeout 5s`. |
| T6 | `d293e82`, `0c87635` | `GET /projects/{id}/run-counts` (new, N19); Overview and Workers from live data, timed refresh with "Updated …", failed parts shown as errors, never zeros. |
| T7 | `9a6eb47` | Playwright E2E (UI-001, UI-002, UI-003, keyboard AC-4) and real-browser axe; `make dashboard-e2e`; CI step in the compose job. |

## Changed files

- `api/src/main/java/dev/testgrid/api/meta/CapabilitiesController.java`: `GET /capabilities` → `{fixturesAllowed}`.
- `api/src/main/java/dev/testgrid/api/run/RunController.java`, `RunQueryService.java`, `RunRepository.java`, `RunFilter.java`: run-list filters, unknown-parameter check, rerun and events endpoints, `parentRunId`/`rerunIds`.
- `api/src/main/java/dev/testgrid/api/run/RerunService.java`, `RerunRequest.java`, `RunCountsController.java`: rerun; 24 h counts.
- `api/src/main/java/dev/testgrid/api/events/*`: `RunEventStreams` (streams, LISTEN, ping), `RunEventRepository`, `SseProperties`.
- `api/src/main/java/dev/testgrid/api/problem/ProblemType.java`: `INVALID_RERUN`, `RATE_LIMITED`.
- `api/src/main/resources/db/migration/V5__run_list_filters.sql`, `V6__rerun_link.sql`, `V7__run_events_notify.sql`.
- `api/pom.xml`: PostgreSQL driver to compile scope (`PGConnection.getNotifications`).
- `api/openapi.yaml`, `packages/contracts/src/api.ts`: regenerated (`make contracts`).
- `docs/API_AND_DATA_CONTRACTS.md`: §4 endpoints and rules, §5 stream rules, §6 `invalid-rerun`, required-index note.
- `docs/CONFLICT_REGISTER.md`: N18 (capabilities), N19 (run counts).
- `web/src/**`: app shell, pages (Overview, Runs, run detail, Suites, Workers), new-run dialog, `useRunEvents`, components, tests.
- `web/e2e/*`, `web/playwright.config.ts`: E2E and browser axe.
- `web/nginx.conf`: `/api/` proxy, 5 s connect timeout. `web/Dockerfile`, `.dockerignore`: tokens and contracts in the image.
- `Makefile`: `dashboard-e2e`. `.github/workflows/ci.yml`: dashboard E2E step and report upload.
- `docs/VERSIONS.md` §1.5: every new web library pin.

## Actual executed commands and outputs

```text
$ make check
… prettier --check . / eslint / tsc (demo-shop, worker, web, contracts) / ./mvnw spotless:check compile
exit 0

$ make test
demo-shop test: ℹ pass 42   ℹ fail 0
worker test:    ℹ pass 82   ℹ fail 0
web test:       Test Files 13 passed (13)   Tests 235 passed (235)
api (surefire): 58 classes, tests=373, failures=0, errors=0, skipped=0
exit 0

$ bash tools/vocab_check.sh
Non-canonical spellings: none
vocab_check OK

$ python3 tools/progress.py lint
lint OK: tracker matches PRD files

$ make up && pnpm --filter web e2e        (full output: raw/prd-05-dashboard-e2e.txt)
  ✓ 14 axe checks (7 pages × dark, light)
  ✓ UI-002 … PASSED with PASS, FAILED with PRODUCT_DEFECT … (16.9s)
  ✓ AC-4: keyboard only, start a run, land on its page, and cancel it (9.2s)
  ✓ UI-001 / AC-3: the stream is blocked mid-run … then goes live again (56.0s)
  ✓ UI-003 / AC-5: rerunning failed targets makes a linked run with only those targets (33.8s)
  18 passed (2.3m)
exit 0

$ curl -s -N -m 2 http://127.0.0.1:3000/api/v1/runs/a01d92ad-…/events      (through nginx)
HTTP/1.1 200
Content-Type: text/event-stream
id:1546
event:RESYNC
data:{"runId":"a01d92ad-51f0-4790-8725-9b43bba87b77","eventId":1546}
```

Focused API runs (from the last `make test`): `RunEventStreamTest` 9/9, `RunEventStreamSweepTest` 2/2, `RerunApiTest` 10/10, `RunListFilterApiTest` 15/15, `RunCountsApiTest` 5/5, `CapabilitiesApiTest` 2/2, `CapabilitiesLocalProfileTest` 1/1, `RunListExplainTest` 1/1, `SchemaConstraintsTest` 14/14. Query plans for the filtered run list (index range scans, no sort, 21 rows read): `raw/prd-05-explain-runs-list.txt`.

## Negative / failure tests

Every new test set was checked by breaking the code on purpose and confirming a test failed, then restoring the file (compared byte for byte):

- API: `FAILED` set including CANCELLED; no "must be finished" check (rerun); status condition dropped from the list query; NOTIFY ignored (stream tests); ping without the sweep.
- Web: new idempotency key per send; fixture section ignoring capabilities; form not locked while posting; Esc closing while busy; cases loaded eagerly; uppercase SHA accepted; cursor not sent; history replaced instead of pushed; comma lists not split; no focus after Load more; wrong API parameter name; oldest attempt shown in the matrix; cancel without confirmation; PASSED counted as failed; empty matrix flashed while loading; older event ids not ignored; no fallback; always polling; RESYNC treated as ordinary; stream kept after finish; reconnect watchdog removed; counts error hidden; wrong counts window; "last seen" not ticking; workers not refreshed; LOST group header missing.
- E2E: AC-3 with the polling fallback disabled in a rebuilt web image failed at the banner.
- Failures injected live: API container stopped mid-run (twice), a 404/429 from the stream, a hanging reconnect behind nginx (found the 60 s default proxy timeout; fixed with 5 s plus a 10 s client watchdog).

## Security and threat check

- Input restrictions: the new-run dialog has no URL, script, regex or Playwright-flag field (unit test lists every form control). The rerun body has only `scope` and `idempotencyKey` and is parsed strictly. Run-list filters reuse the create-run rules; unknown enum values and **unknown query parameters** are `400`, so a misspelt filter never returns the unfiltered list. Filter values are bound parameters; the SQL text depends only on which filters are present (test asserts it). `Last-Event-ID` must be a non-negative integer.
- Scope: every new endpoint resolves the caller's project first; a run or project outside it is `404` before any stream opens.
- Disclosure: `GET /capabilities` returns a boolean, not the profile name. Problem bodies carry the trace ID only.
- Resource use: at most 100 open streams (`429 rate-limited`, `Retry-After: 5`); streams close after 30 minutes; one LISTEN connection; polling only as a fallback.
- Secret or artifact leak scan: no secrets added; only `.env.example` fake values used; logs and evidence files contain run IDs and worker names only.
- New attack surface: the SSE endpoint (long-lived connections) is capped and scoped; `X-Accel-Buffering: no` affects buffering only.

## Skipped or mocked tests

- Web component tests (Vitest, 235) run against a **fake API** (`web/src/test/api.ts`), a **fake EventSource** (`web/src/test/eventSource.ts`; jsdom has none) and a `<dialog>` stand-in (jsdom lacks `showModal`). Real browser behaviour (modal inertness, EventSource reconnect with `Last-Event-ID`) is covered by the live E2E and manual checks.
- Component axe runs with colour contrast **off** (no layout in jsdom); the E2E axe run has it on.
- The E2E runs on **Chromium only**. Firefox and WebKit were not run for the dashboard.
- AC-3's "kill the connection" is done by restarting the web proxy, with Playwright blocking reconnects; Playwright cannot end a stream already flowing, and `context.setOffline(true)` did not cut it (tried first).
- GitHub Actions has not run on this branch (not pushed).

## Screenshots / demo / raw data links

- `docs/evidence/raw/prd-05-dashboard-e2e.txt`: full Playwright output, 18 passed.
- `docs/evidence/raw/prd-05-explain-runs-list.txt`: `EXPLAIN (ANALYZE, BUFFERS)` for the run list, unfiltered and filtered.

## Known limitations and deferred improvements

- The Overview's status tiles are not links: the run list has no time filter, so a link would show more runs than the tile counts. A `queuedAfter` filter would make them honest links.
- "Last seen" and relative times use the browser's clock against server timestamps; exact on one machine, could drift across machines.
- Overview and Workers refresh on a timer (15 s / 10 s); they have no event stream.
- The stream cap and subscriber registry are per API instance (one instance today). The LISTEN connection takes one connection from the pool.
- A rerun requires the old run's suite revision to still be current and enabled (`422 unknown-suite` otherwise).
- Opening a case from the matrix, artifacts and the timeline are PRD-06 (UI-002's matrix row is marked 05/06).
- During T4, one `make test` saw a run created through the API already terminal when it was rerun. A full-suite rerun of the old setup did not reproduce it and the cause is unknown; the test now builds its runs in SQL. Commit `b66b726` first blamed shared test databases; `cd824de` corrects that.
- Leftover cached Spring contexts keep their background loops running for the rest of a Maven run; each has its own database, so no interference was observed, but it costs memory.

## Explain-back review (developer's own words)

The PRD says the developer must be able to explain SSE replay and cache reconciliation without AI. Claude has not answered these; the developer fills them in before `tools/progress.py done`.

1. The three most important engineering decisions in this PRD, and why: [developer to answer]
2. What happens if the main operation fails halfway through? (For example: the stream drops while a target finishes, or the rerun POST's answer is lost.) [developer to answer]
3. What would you change for production? [developer to answer]

Explain-back quiz:

4. A client reconnects with `Last-Event-ID: 41`. Which rows does the server read, in what order, and when does it send `RESYNC` instead? [developer to answer]
5. Why does the page refetch `GET /runs/{id}` on every event instead of applying the event to the cache, and how are a repeated event and an older event handled? [developer to answer]
6. `pg_notify` is called inside the transaction that inserts the event. What does that guarantee, and what still makes delivery correct if a notification is lost? [developer to answer]
7. After two failed reconnects the page polls. What exactly counts as a failed reconnect, and when does polling stop? [developer to answer]
8. Why does the new-run dialog keep one idempotency key per distinct request body rather than one per click or one per dialog? [developer to answer]
9. Why do the run-list filters reject unknown query parameters, and why are the filter indexes `(project_id, status, queued_at DESC, id DESC)` rather than `(project_id, status)`? [developer to answer]

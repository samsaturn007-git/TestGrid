# TestGrid: design brief for Claude Design

> **How to use this file:** give it to Claude Design as-is. It describes the product, its users, the exact status vocabulary, every screen and its states, real sample data, and the constraints the frontend must meet. Ask for design inspiration first (section 13), then mockups of the PRD-05 screens, then the later screens.

---

## 1. The product in one paragraph

TestGrid is a local-first, reliability-first **cross-browser test orchestration platform**. A developer picks a registered Playwright test suite (for example `checkout-smoke`) and the browser engines to run it on (Chromium, Firefox, WebKit). TestGrid queues the work durably, sends it to a capped pool of workers, runs real browsers against an owned demo shop, and records the result of every test case on every engine. It survives crashed workers, duplicate messages and broker restarts without ever recording a contradictory or invented result.

The frontend is the **dashboard** where people launch runs, watch them progress live, and work out *why* something failed: was it the product, the test, the browser environment, or the test infrastructure?

**The single most important idea for the design:** TestGrid's main promise is *honesty about status*. A failed test (`FAILED`) and broken infrastructure (`INFRA_ERROR`) are different things, and the UI must never blur them, never show a stale green, and never show a number that didn't come from the API.

## 2. Who uses it

| User | What they want from the UI |
|---|---|
| **Frontend or full-stack engineer** (primary) | Launch a suite on 3 engines, watch it fill in live, open the one failing case, see the screenshot and error, rerun only what failed. |
| **Team lead / reviewer** | Compare a branch run with the last green run; see new failures, fixed cases and slowdowns; quarantine a known-flaky case with a reason. |
| **Operator** | See which workers are online, how many browser sessions are in use against the cap, and anything stuck. |
| **Hiring engineer** (secondary, portfolio) | Understand the system's reliability story in a few minutes from a read-only demo. |

Persona: developers who live in GitHub, terminals and CI logs. They value **density, speed, keyboard control and precision** over decoration.

## 3. Design principles

1. **Honest data.** Every number and status comes from the API. No seeded fake runs, no placeholder charts, no "99.9% uptime" filler. When there is no data, show an empty state that explains how to create some.
2. **Colour is never the only signal.** Every status has an icon *and* a text label *and* an `aria-label`. The design must still work in greyscale and for colour-blind users.
3. **Infra is not failure.** `INFRA_ERROR` and `INDETERMINATE` get their own visual identity, clearly separate from red `FAILED`/`FAIL`.
4. **Live, but never stale.** Things update in place as events arrive. If the live connection drops, say so plainly ("Live updates paused, refreshing every 5s") instead of silently freezing.
5. **History is kept.** Retries don't overwrite: an `ABANDONED` attempt 1 stays visible next to the `PASSED` attempt 2.
6. **Explain, don't assert.** Automatic labels (failure category, flaky) always show *why*: the matched rule, the evidence, the confidence. Never a bare label.
7. **Keyboard first.** Everything works without a mouse: skip link, landmarks, visible focus ring, shortcuts for common actions.
8. **Dense but calm.** A developer tool, not a marketing site. Tables, matrices, monospace for IDs and hashes, restrained colour used mainly for status.

## 4. Visual direction (starting point, open to Claude Design's proposals)

- **Mood:** precise, technical, trustworthy, quiet. Think "instrument panel", not "dashboard template".
- **Themes:** light and dark from day one. Dark is likely the default for this audience.
- **Typography:** a clean UI sans for text; a monospace for run IDs, commit SHAs, case keys (`cart-total`), target keys (`local:chromium:0`), durations and error messages. Use tabular numerals in tables and counters.
- **Colour:** a neutral base (greys) with colour reserved for meaning (status, focus, primary action). Define everything as design tokens: colour, spacing, type scale, radius, elevation, defined once (the build uses Tailwind tokens).
- **Engine identity:** Chromium, Firefox and WebKit appear everywhere (matrix columns, filters, chips). Give each a small neutral glyph or monogram rather than official browser logos, so the design doesn't lean on third-party brands.
- **Motion:** minimal and purposeful: a status chip changing state, a row arriving at the top of a live list, a pulsing "running" indicator. Respect `prefers-reduced-motion`.

### Inspiration to study (look and interaction patterns only; don't copy any of them)

| Reference | What to learn from it |
|---|---|
| GitHub Actions run page | job/step status hierarchy, live log feel, re-run failed jobs |
| Vercel and Netlify deployment lists | calm status rows, live state, commit and branch metadata |
| Linear | density, keyboard-first navigation, command palette, restraint |
| Playwright HTML report and Trace Viewer | case lists, error presentation, the step timeline |
| Buildkite / CircleCI | build matrix and pipeline status at a glance |
| Sentry issues | grouping identical errors, occurrence counts, "first seen / last seen" |
| Grafana | operational panels; useful for the workers and cap view |
| Datadog CI Visibility / BuildPulse | flaky-test history strips and health labels |

## 5. The status system (use these labels exactly)

These enums come from the API contract and must be spelled exactly as below in the UI (upper case, underscores). The design may show a friendlier secondary line, but the chip label is the enum value. Icon and colour are suggestions; Claude Design should propose a coherent set.

### Run, target and attempt statuses

| Status | Applies to | Meaning | Suggested icon / colour role |
|---|---|---|---|
| `QUEUED` | run, target | waiting for a worker | hollow circle · neutral |
| `CLAIMED` | target, attempt | a worker has taken it, browser starting | dotted circle · info blue |
| `RUNNING` | run, target, attempt | browser is executing cases | spinner or pulse · info blue |
| `PASSED` | run, target, attempt | every case passed | check · success green |
| `FAILED` | run, target, attempt | at least one case failed (product or test problem) | cross · danger red |
| `TIMED_OUT` | run, target, attempt | ran out of time | clock / hourglass · warning amber |
| `INFRA_ERROR` | run, target, attempt | the infrastructure failed, not the test | plug or server-off · distinct violet/purple, never red |
| `CANCELLED` | run, target, attempt | someone cancelled it | slashed circle · neutral grey |
| `ABANDONED` | attempt only | the worker vanished; the lease expired and the attempt was given up (it's kept in history) | broken link / ghost · muted orange-grey |

### Case statuses

| Status | Meaning | Suggested |
|---|---|---|
| `PASSED` | passed first time | check · green |
| `FAILED` | failed | cross · red |
| `TIMED_OUT` | the case hit its timeout | clock · amber |
| `SKIPPED` | not run | dash · grey |
| `FLAKY` | failed, then passed on an automatic retry in the same run | zig-zag · yellow, clearly not plain green |

### Other labels that need a visual treatment

| Enum | Values | Where it shows |
|---|---|---|
| `ErrorClass` | `ASSERTION, TEST_TIMEOUT, SELECTOR_NOT_FOUND, BROWSER_LAUNCH, WORKER_LOST, WORKER_SHUTDOWN, LEASE_EXPIRED, NETWORK, ARTIFACT_STORE, CONFIG, CANCELLED, UNKNOWN` | small monospace tag next to a failed case or attempt |
| `FailureCategory` | `PRODUCT_SUSPECTED, TEST_BROKEN, AUTOMATION_FLAKE, TIMEOUT, ENVIRONMENT, UNKNOWN` | failure groups, always with confidence and rule |
| `QualityOutcome` | `PASS, FAIL, INDETERMINATE` | the CI quality gate panel; `INDETERMINATE` means "couldn't decide (infra)" and must look different from `FAIL` |
| Comparison | `NEW_FAILURE, FIXED, STILL_FAILING, STILL_PASSING, FLAKY_IN_HEAD, ADDED, REMOVED, NOT_COMPARABLE` | the run comparison page |
| Case health | `STABLE, FLAKY, FAILING, INSUFFICIENT_DATA` | case history and flaky analytics |
| Worker state | `ACTIVE, DRAINING, LOST` | workers page |
| Artifact state | `AVAILABLE, EXPIRED, MISSING, PURGED` | screenshot / trace / log badges |
| `Trigger` | `MANUAL, CI, RERUN, BENCHMARK, CHAOS` | run metadata chip |
| `FixtureMode` | `PASS, PRODUCT_DEFECT, AUTOMATION_FLAKE, SELECTOR_DRIFT, TIMEOUT` | new-run dialog and run metadata (local profile only) |
| `Engine` | `chromium, firefox, webkit` (lower case) | matrix columns, chips, filters |

**Deliverable:** one `StatusBadge` component sheet showing every value above in light and dark, at two sizes (chip, and dot plus label for dense tables).

## 6. How a run works (so the screens make sense)

```
Run (one suite, one or more engines)
 └─ Target  = one engine, e.g. local:chromium:0      status: QUEUED → CLAIMED → RUNNING → terminal
     └─ Attempt 1, 2, 3 …  (a retry creates a new attempt; old ones are kept)
         └─ Case results    e.g. cart-total FAILED (ASSERTION) 1.2 s
```

- A run's own status is computed from its targets: if any target `FAILED`, the run is `FAILED`; then `TIMED_OUT`, `INFRA_ERROR`, `CANCELLED`; it's `PASSED` only when every target passed.
- A target can be retried up to 3 times. A killed worker produces `ABANDONED` (error `LEASE_EXPIRED`) and then a new attempt on another worker.
- A global cap limits how many browser sessions run at once (3 by default). Targets beyond the cap wait as `QUEUED`.
- Live updates arrive as events: `RUN_CREATED`, `TARGET_DISPATCHED`, `TARGET_CLAIMED`, `TARGET_STARTED`, `CASE_FINISHED`, `ATTEMPT_ABANDONED`, `TARGET_RETRY_SCHEDULED`, `TARGET_FINISHED`, `RUN_CANCEL_REQUESTED`, `RUN_FINISHED`, later `ARTIFACT_AVAILABLE` and `QUALITY_EVALUATED`.

## 7. Sitemap and build order

The dashboard is built in phases. **Design PRD-05 in full detail first**; the later phases can be lower fidelity, but the shell and navigation should leave room for them.

| Route | Screen | Phase |
|---|---|---|
| `/` | Overview | PRD-05 |
| `/projects/:id/suites` | Suites | PRD-05 |
| (dialog) | New run | PRD-05 |
| `/runs` | Run list | PRD-05 |
| `/runs/:id` | Run detail with browser matrix | PRD-05 |
| `/workers` | Workers | PRD-05 |
| (drawer on run detail) | Case failure detail: screenshot, trace, log | PRD-06 |
| (tab on run detail) | Attempt timeline | PRD-06 |
| `/failures`, `/failures/:id` | Failure groups | PRD-07 |
| `/cases/:caseKey` | Case history | PRD-07 |
| `/compare?base=&head=` | Run comparison | PRD-07 |
| (panel on run detail) | Quality gate | PRD-08 |
| `/projects/:id/policy` | Quality policy and versions | PRD-08 |
| `/quarantines` | Quarantines | PRD-08 |
| `/chaos` | Chaos drill scoreboards | PRD-10 |
| (card / panel) | Scheduler settings, shard view, benchmark chart | PRD-11 |
| static demo | Read-only public demo with "recorded sample" label | PRD-12 |
| (optional) | Visual diff review | PRD-14 |
| (optional) | AI failure explanation panel | PRD-15 |

Navigation: a slim left sidebar (Overview, Runs, Suites, Failures, Workers, and later Quarantines, Chaos) plus a top bar with project name, global search / command palette (`⌘K`), theme toggle and a "New run" primary button.

## 8. Screens in detail: PRD-05 (design these first)

### 8.1 App shell
- Skip link ("Skip to content"), landmarks (`header`, `nav`, `main`), a visible focus ring on everything.
- A small connection indicator in the top bar: `Live` (connected), `Reconnecting…`, `Live updates paused, refreshing every 5s`.
- Keyboard shortcuts shown in tooltips and a `?` help overlay (e.g. `N` new run, `G R` go to runs, `/` focus filter).

### 8.2 Overview `/`
- Status counts for the **last 24 h**: one tile per run status (`PASSED`, `FAILED`, `TIMED_OUT`, `INFRA_ERROR`, `CANCELLED`, plus in-progress `QUEUED`/`RUNNING`). Each tile links to the filtered run list.
- Recent runs: the latest ~10 rows, live.
- Workers online: count by state, and **active sessions vs cap** (e.g. `2 / 3 sessions`, with `1 dispatched, not yet claimed`).
- No charts beyond counts in this phase.
- **Empty state** (fresh install): explain that runs come from registered suites and offer "Start your first run".

### 8.3 Suites `/projects/:id/suites`
- Suite cards: display name, `suiteKey`, case count, revision (12-character hash, monospace), allowed engines as chips, "Run" button.
- Two real suites today: **Checkout smoke** (`checkout-smoke`, 6 cases) and **Catalog regression** (`catalog-regression`, 12 cases).
- Card detail or expand: the case list (case key, title, tag such as `@cart-total`).

### 8.4 New-run dialog
- Suite (select), engines (multi-select checkboxes: chromium, firefox, webkit; 1 to 3, limited to the suite's allowed engines).
- **Fixture mode** (local profile only, hidden otherwise): `PASS, PRODUCT_DEFECT, AUTOMATION_FLAKE, SELECTOR_DRIFT, TIMEOUT`, plus a numeric seed. Explain each mode in one line (e.g. "PRODUCT_DEFECT: the cart total is off by 2.00").
- Optional source metadata: branch, commit SHA.
- Sharding controls exist in the API but are **hidden until PRD-11**; don't design them into this dialog yet.
- **No free-text URL, script or code fields.** Runs can only use registered suites (a security rule; see section 11).
- Submit states: submitting; created (go to run detail); "This run already exists" (an idempotent replay returns the same run: show it as information, not an error); validation errors inline on the field.

### 8.5 Run list `/runs`
- Columns: status chip, suite, engines (small chips, each with its target's status), trigger, fixture mode, branch / commit, queued time (relative, with absolute on hover), duration, run ID (short, monospace, copyable).
- Filters: status, suite, branch, trigger. **Filters are synced to the URL** so a shared link shows the same view. Show active filters as removable chips.
- Pagination: a "Load more" button (cursor-based; no page numbers).
- New runs appear at the top live, with a subtle arrival highlight.
- States: loading (skeleton rows), empty ("No runs match these filters" with a clear-filters action; first-use empty state differs), error (Problem Details message plus trace ID, with retry).

### 8.6 Run detail `/runs/:id` (the hero screen)
- **Header:** large aggregate status, suite and revision, trigger, fixture mode and seed, branch and commit, queued / started / finished times and total duration, run ID. Actions: **Cancel** (only while not terminal), **Rerun failed targets**, **Rerun all**. A rerun links to its parent run, and the parent shows "Rerun: #…" in both directions.
- **Browser matrix:** rows = cases (e.g. `browse-catalog`, `add-to-cart`, `cart-total` …), columns = engines (chromium, firefox, webkit). Each cell is a status chip for the latest attempt's case result; click or Enter opens the case detail. Column headers show the target's status, attempt number (e.g. "attempt 2 of 3") and worker. While running, cells fill in live as `CASE_FINISHED` events arrive; cells not yet reached look pending.
- **Per-target attempts list:** each attempt with number, status, error class, worker, claimed / started / finished times, last heartbeat, and a clear "lease expired" marker. Abandoned attempts stay visible, e.g. `1 ABANDONED (LEASE_EXPIRED) on worker-bff2… → 2 PASSED on worker-bee2…`.
- **Case table** (expandable): case key, title, status, duration, retry count, error class, first line of the error message. Sortable by status and duration.
- **Cancel flow:** confirm dialog; then "Cancel requested": queued targets become `CANCELLED` at once, running ones finish their current step and then show `CANCELLED`. If the run had already finished, show "Run already finished; nothing to cancel".
- Live connection banner when updates are paused.

### 8.7 Workers `/workers`
- Summary: `activeSessions / globalMaxSessions` and `dispatchedUnclaimed`.
- Table: worker name (e.g. `worker-bee2be453300`, monospace), state (`ACTIVE`, `DRAINING`, `LOST`), engines, slots in use / slots, version, started, last seen ("12 s ago"). Live workers first, then `LOST` in the last hour, visually de-emphasised.

## 9. Later screens (lower fidelity is fine)

- **Case failure drawer (PRD-06):** error class, redacted error message (monospace, wrapped), screenshot via a short-lived link, "Download trace" with a copyable `npx playwright show-trace <file>` hint, log tail, and artifact state badges. Expired artifacts show a clear message, not a broken image.
- **Attempt timeline (PRD-06):** a horizontal timeline per attempt: claimed → started → one bar per case (length = duration) → finished, with artifact markers, and any **heartbeat gap longer than 2× the heartbeat interval highlighted**. Later, chaos fault-injection markers (PRD-10).
- **Failure groups (PRD-07):** a list sorted by occurrence count with category, confidence (e.g. 0.82) and engines. Detail: normalised message, raw examples, occurrences across runs and engines, the **matched rule and its evidence**, and a "Change category" override form that requires a reason and shows the audit history.
- **Case history (PRD-07):** per engine, an outcome strip of the last 20 results (each a small status square linking to its run), a duration sparkline, and the health label with *why* (e.g. "FLAKY: 6 flips in 19 transitions").
- **Run comparison (PRD-07):** base vs head header; results grouped by `NEW_FAILURE`, `FIXED`, `STILL_FAILING` (with "fingerprint changed" marker), `FLAKY_IN_HEAD`, `ADDED`, `REMOVED`, `NOT_COMPARABLE`, `STILL_PASSING` (collapsed); duration regressions highlighted. Reached from run detail via "Compare with last green".
- **Quality gate panel (PRD-08):** overall `PASS / FAIL / INDETERMINATE`, then a rule table: rule ID, observed, threshold, outcome, blocking, evidence links, plus the policy version used. Quarantined failures show as `QUARANTINED_FAIL` with a note.
- **Quarantines (PRD-08):** case, engine, reason, who, expiry countdown (maximum 30 days), remove action; a "Quarantine?" suggestion on `FLAKY` cases.
- **Chaos (PRD-10):** read-only drill scoreboards (drill name, repeats, pass count, recovery time p50/max, invariant results I1 to I9).
- **Scheduler (PRD-11):** policy card (FIFO vs LPT with aging), prediction source per target (`history`, `manifest`, `default`), shard view, and a benchmark box plot rendered from committed data.
- **Public demo (PRD-12):** the same dashboard, read-only, with a persistent "Recorded sample" banner and execution controls hidden.
- **Optional:** visual diff review with baseline / current / diff panes and a slider (PRD-14); an AI explanation panel with an "AI-generated suggestion: verify with evidence" banner, citations and thumbs up/down (PRD-15).

## 10. Realistic sample data (use this, not lorem ipsum)

**Suites and cases**
- `checkout-smoke` (Checkout smoke, timeout 120 s): `browse-catalog`, `add-to-cart`, `cart-total`, `apply-coupon`, `checkout-happy-path`, `card-declined`.
- `catalog-regression` (Catalog regression, timeout 180 s): `catalog-count`, `catalog-order`, `catalog-prices`, `catalog-links`, `product-detail-lamp`, `product-detail-bottle`, `product-not-found`, `route-not-found`, `home-link`, `cart-empty-state`, `cart-badge`, `discounted-total`.

**What each fixture mode really produces** (observed on all three engines)
- `PASS` → run `PASSED`, 6/6 cases `PASSED` per engine.
- `PRODUCT_DEFECT` → run `FAILED`; `cart-total` `FAILED` / `ASSERTION`: *Expected "$80.99", Received "$82.99"*.
- `SELECTOR_DRIFT` → run `FAILED`; `checkout-happy-path` and `card-declined` `FAILED` / `SELECTOR_NOT_FOUND`: *locator.click: Timeout 5000ms exceeded*.
- `TIMEOUT` → run `TIMED_OUT`; the same two cases `TIMED_OUT` / `TEST_TIMEOUT`.
- `AUTOMATION_FLAKE` (seed 0) → `checkout-happy-path` `FLAKY` (failed, then passed on retry).
- A killed worker → target history `1 ABANDONED (LEASE_EXPIRED) → 2 PASSED`, about 30 to 40 s later, on a different worker.

**A real run detail response** (`GET /api/v1/runs/{id}`, one target shown)

```json
{
  "id": "355f5da2-0f63-4ecb-88a6-2c852ca77aa1",
  "suiteKey": "checkout-smoke",
  "suiteRevision": "29d6afdd879b",
  "status": "FAILED",
  "trigger": "MANUAL",
  "fixture": { "mode": "PRODUCT_DEFECT", "seed": 42 },
  "source": null,
  "queuedAt": "2026-11-20T11:52:26.124438Z",
  "startedAt": "2026-11-20T11:52:26.505931Z",
  "finishedAt": "2026-11-20T11:53:08.535201Z",
  "cancelRequestedAt": null,
  "targets": [
    {
      "targetKey": "local:chromium:0",
      "engine": "chromium",
      "status": "FAILED",
      "attemptCount": 1,
      "maxAttempts": 3,
      "latestAttempt": {
        "attemptNo": 1,
        "workerId": "worker-1",
        "status": "FAILED",
        "claimedAt": "2026-11-20T11:52:26.505931Z",
        "startedAt": "2026-11-20T11:52:26.520904Z",
        "finishedAt": "2026-11-20T11:52:38.871578Z",
        "leaseExpired": false,
        "lastHeartbeatAt": "2026-11-20T11:52:36.535921Z",
        "errorClass": "ASSERTION"
      }
    }
  ]
}
```

**Workers response shape** (`GET /api/v1/workers`): `items[{name, state, engines, slots, activeAttempts, startedAt, lastSeenAt, version}]`, plus `globalMaxSessions: 3`, `activeSessions`, `dispatchedUnclaimed`.

**Errors** come back as Problem Details: `{type, title, status, detail, traceId}`, e.g. `409 idempotency-conflict`, `422 unknown-suite`. Show the title and detail, and offer the trace ID as copyable small print.

## 11. Constraints the design must respect

- **Tech:** React 19 + TypeScript, React Router, TanStack Query, Tailwind with tokens defined once, generated API types. Live updates over server-sent events. Design components that map cleanly to this (no exotic layout tricks needed).
- **Security boundary:** users can't enter URLs, upload test code, scripts or regexes, or pass raw Playwright flags. Runs come only from the registered suite manifest. **Don't design inputs for any of these.**
- **Fixture modes** appear only in the local profile; design the dialog with and without them.
- **No fake metrics.** No mock charts on the overview, no invented trends. If a widget needs data the API doesn't have yet, leave it out of that phase.
- **Accessibility:** WCAG 2.2 AA contrast in both themes, no information by colour alone, every interactive element reachable and operable by keyboard, `aria-live` (polite) for live status changes, focus kept sensible when drawers and dialogs open and close, targets at least 24 × 24 px. The build is checked with axe: no serious or critical violations.
- **Responsive:** desktop first (1280 to 1600 px wide is the main use); usable down to tablet width; the browser matrix may scroll horizontally on small screens with the case column sticky.

## 12. Component inventory

App shell (sidebar, top bar, command palette, theme toggle, connection indicator) · StatusBadge (all enums, two sizes) · EngineChip · ErrorClassTag · DurationText · RelativeTime · CopyableId · Filter bar with URL-synced chips · Data table (sortable, sticky header, skeleton rows) · Browser matrix · Attempt list item · Case table · Run header · Primary / secondary / danger buttons · Confirm dialog · New-run dialog · Drawer · Toast / inline alert · Empty state · Error state with trace ID · Live-paused banner · Stat tile · Worker row · (later) Timeline track, outcome strip, sparkline, rule table, comparison group, diff viewer.

## 13. What to deliver

1. **Design inspiration board:** 2 or 3 distinct visual directions (for example "terminal-precise dark", "calm editorial light", "high-contrast instrument"), each with palette, type pairing, a status-chip row and one sample screen fragment. Note what each borrows from the references in section 4.
2. **Design tokens** for the chosen direction: colour (including every status colour in light and dark with contrast ratios), spacing, type scale, radius, elevation, focus ring.
3. **StatusBadge sheet** covering every value in section 5.
4. **High-fidelity mockups of the PRD-05 screens:** overview, suites, new-run dialog, run list, run detail (queued, running live, and finished-with-failures states), workers. Show empty, loading, error and live-paused states for the run list and run detail.
5. **One worked flow:** keyboard-only "create run → open detail → watch it fill in → cancel".
6. **Lower-fidelity frames** for the later screens in section 9, so the navigation and layout leave room for them.

## 14. Out of scope for the design

- The **demo shop** (catalog, cart, checkout) is a test fixture with its own simple UI. Don't redesign it.
- Marketing or landing pages, sign-up, billing, teams and permissions UI (beyond the scoped-token note in PRD-12).
- Official browser or vendor logos, and any real company's branding.

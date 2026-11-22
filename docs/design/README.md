# TestGrid dashboard design: handoff

The visual design for the dashboard, from PRD-05 onward. The full design lives on a Claude Design canvas. This folder is the versioned copy that the frontend build reads from.

| File | What it is |
|---|---|
| [`CLAUDE_DESIGN_BRIEF.md`](CLAUDE_DESIGN_BRIEF.md) | The brief the design was made from: users, principles, status vocabulary, screens and constraints. |
| [`tokens.css`](tokens.css) | Every token as a CSS variable (`--tg-*`). Dark by default; light with `[data-theme="light"]` or the OS setting. |
| [`tokens.json`](tokens.json) | The same tokens in machine-readable form, plus `statusMap` (enum value → colour role + icon) and the icon paths. |

**Canvas:** https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8 (private until it is shared from its Share menu). Add `#artboard-<Name>` to the URL to open a single board. To read a board from a Claude session, use the Artifact tool's `read` action with `path: "project/<Name>.dc.html"`.

## Chosen direction: A · Signal

Terminal-precise and dark by default. Near-black greys with hairlines instead of shadows, and colour used only for status, focus and nothing else. Inter is the UI face and JetBrains Mono is used for IDs, SHAs, keys, durations and errors. Numbers use tabular numerals throughout. B · Ledger and C · Instrument were considered and stay on the canvas for reference.

## Screens and where they go

| Canvas board | Route / place | PRD | Fidelity |
|---|---|---|---|
| [StatusBadges](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-StatusBadges) | `StatusBadge` component: every enum, dark and light, chip and dense sizes | 05 | high |
| [Tokens](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-Tokens) | Tailwind theme (`tokens.css` / `tokens.json`) | 05 | high |
| [Overview](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-Overview) | `/`, plus the app shell that every page reuses | 05 | high |
| [Suites](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-Suites) | `/projects/:id/suites` | 05 | high |
| [NewRun](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-NewRun) | New-run dialog: local profile, other profiles, submit states | 05 | high |
| [RunList](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-RunList) | `/runs` | 05 | high |
| [RunListStates](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-RunListStates) | `/runs`: loading, live paused, error, filtered empty, first use; overview on a fresh install | 05 | high |
| [RunDetail](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-RunDetail) | `/runs/:id`, finished with failures | 05 | high |
| [RunDetailRunning](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-RunDetailRunning) | `/runs/:id`, running live | 05 | high |
| [RunDetailStates](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-RunDetailStates) | `/runs/:id`: queued, cancel requested, live paused, cancel confirm, already finished, not found | 05 | high |
| [Workers](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-Workers) | `/workers` | 05 | high |
| [KeyboardFlow](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-KeyboardFlow) | Keyboard-only flow (create, open, watch, cancel) and the `?` overlay | 05 | high |
| [LaterPRD06](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-LaterPRD06) | Case failure drawer, attempt timeline | 06 | low |
| [LaterPRD07](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-LaterPRD07) | `/failures`, `/failures/:id`, `/cases/:caseKey`, `/compare` | 07 | low |
| [LaterPRD08](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-LaterPRD08) | Quality gate panel, `/projects/:id/policy`, `/quarantines` | 08 | low |
| [LaterPRD10](https://claude.ai/artifact/JNMzxqEGt753do4U4FGMw8#artboard-LaterPRD10) | `/chaos`, scheduler, public demo, visual diff, AI panel, full nav with ⌘K | 10–15 | low |

The canvas shows only the dark theme for the screens. Light is fully specified in the tokens and on the StatusBadge sheet.

## Rules the design depends on

- **Labels are the enum.** A chip label is the exact API value (contracts §2.1). Each status has an icon, a text label and an `aria-label`, so the UI still reads in greyscale.
- **Infra is not failure.** `INFRA_ERROR`, `INDETERMINATE`, `ENVIRONMENT` and infrastructure error classes (`LEASE_EXPIRED`, `WORKER_LOST`, `BROWSER_LAUNCH`, …) use the violet `infra` role and are never red. `FAILED` red covers product and test problems.
- **History is kept.** An `ABANDONED` attempt stays listed above its retry and keeps its lease-expired marker.
- **Live, never stale.** The connection indicator has three states: `Live`, `Reconnecting…`, and `Live updates paused, refreshing every 5s`. While paused, the page shows the last refresh time and RUNNING icons stop pulsing.
- **Live list rows:** new rows get a 1.2 s info tint and a 2px edge. Status changes reach an `aria-live="polite"` region, batched every 2 s; a failure is announced at once.
- **Matrix:** one Tab stop with a roving tabindex, arrow keys between cells, Enter to open the case. The case column is sticky when the matrix scrolls sideways.
- **Dialogs:** focus moves into the dialog and is trapped until it closes; Esc closes it and returns focus to whatever opened it. The cancel confirmation opens with focus on "Keep running". After a cancel, focus moves to the status banner, because the Cancel button is disabled.
- **No invented inputs.** The new-run dialog has no URL, script, regex or flag fields (ADR-003). Fixture modes appear only in the local profile. Sharding stays hidden until PRD-11.
- **Errors** show the Problem Details `title` and `detail`, with `traceId` as copyable small print. An idempotent replay ("This run already exists") is shown as information, not an error.
- **Attempt timeline gap:** highlight any heartbeat gap longer than 2 × `heartbeatIntervalSeconds` (returned by `POST /workers/register`). The canvas assumes 5 s.

## Shortcuts

| Keys | Action |
|---|---|
| `N` | New run |
| `⌘K` | Command palette |
| `/` | Focus the filter |
| `G` then `O` / `R` / `S` / `W` | Go to Overview / Runs / Suites / Workers |
| `R` | Rerun failed targets (run detail) |
| `⇧C` | Cancel run (run detail, while not terminal) |
| `⌘↵` | Submit the new-run dialog |
| `Esc` | Close dialog or drawer |
| `?` | Shortcut overlay |

Single-key shortcuts do nothing while focus is in a text field.

## Sample data on the canvas is not an API fact

The mockups use the brief's real sample data (run `355f5da2`, `checkout-smoke` at `29d6afdd879b`, the `$80.99` / `$82.99` assertion, the fixture-mode outcomes, `worker-bee2be453300`). The designer invented these values to fill the screens; do not copy them into code or tests:

- worker names other than `worker-bee2be453300`, worker version `0.5.0`, and one slot per worker;
- the `catalog-regression` revision `b4e07c9a1d52`, case titles, case durations other than `1.2 s`, commit SHAs, branch names, and run IDs other than `355f5da2`;
- trace IDs, failure-group confidences other than `0.82`, rule names, policy versions and thresholds, and quarantine entries;
- the 5 s heartbeat interval.

Bracketed values such as `[n]`, `[p50]`, `[min]` and `[threshold]` mark data the API does not provide yet. Leave those widgets out until it does.

## How the web app reads these files

- `web/src/styles/app.css` imports `tokens.css` and maps every `--tg-*` variable into Tailwind's `@theme`. The default Tailwind palette, radii, shadows and type scale are cleared, so only token utilities exist (`bg-danger-tint`, `rounded-chip`, `text-mono-sm`, …).
- `web/src/components/status.ts` imports `tokens.json` and uses `statusMap` and `icons` as they are. Each table is typed as a `Record` over the generated API enum, so the build fails if the API gains a status that has no badge here.
- The web image copies only `tokens.css` and `tokens.json` from this folder (`.dockerignore` exceptions).
- Fonts are self-hosted with `@fontsource-variable`, which registers `Inter Variable` and `JetBrains Mono Variable`. `tokens.css` lists those names first; the plain names remain as fallbacks.
- `tokens.css` defines `.tg-pulse` (the RUNNING icon animation) and turns it off under `prefers-reduced-motion`.

## Keeping this in sync

The canvas is where the design is edited. When a board changes, update this README and the token files in the same PRD branch, so the repo never describes a design the canvas no longer has.

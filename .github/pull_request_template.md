## PRD and scope

- **Active PRD:** PRD-XX — <!-- exactly one PRD per PR (AGENTS.md rule 1) -->
- **Tasks covered:** T?
- **Branch:** `prd-XX/<topic>`

## What changed and why

<!-- Short summary. Link any new or changed ADR in docs/decisions/. -->

## Evidence checklist

- [ ] Only the active PRD's scope is touched; nothing from a future PRD.
- [ ] Every acceptance criterion touched here is listed in `docs/evidence/PRD-XX.md` with its test ID from `docs/SYSTEM_TEST_MATRIX.md`.
- [ ] Commands that were actually run are quoted with real output and exit codes. Nothing is claimed as passing that wasn't executed.
- [ ] Mocked vs live tests are labelled; skipped tests are listed with a reason.
- [ ] Negative / failure paths were exercised, not only the happy path.
- [ ] Contracts are consistent: if API, DB, events or enums changed, `docs/API_AND_DATA_CONTRACTS.md`, migrations, OpenAPI, generated types and tests are updated in this PR.
- [ ] Status, error-class and problem-type spellings come from contracts §2 / §6 — no new spellings.
- [ ] Any new library or image version is verified against its official source and recorded in `docs/VERSIONS.md`.

## Security and threat check

- [ ] No client-supplied URLs, file paths, shell fragments, regexes, scripts or Playwright flags.
- [ ] No secrets in code, logs, fixtures, screenshots, traces or CI artifacts; `.env.example` holds fake values only.
- [ ] Ports bind to `127.0.0.1`; no new public endpoint or Docker socket mount.
- New attack surface (if any) and its mitigation:

## Explain-back

<!-- In your own words: the most important decision in this PR and its trade-off. -->

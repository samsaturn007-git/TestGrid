# Contributing to TestGrid

Solo project, but run like a team repository so every PRD can be reviewed against evidence. Standing agent rules live in `AGENTS.md`; this file holds the git conventions.

## One PRD at a time

- Exactly one PRD is `in_progress` in `progress.json` (enforced by `tools/progress.py`).
- Each PRD gets its own branch and its own pull request into `main`.
- Work that belongs to a later PRD waits, or gets a written change record approved by the developer.

## Branches

| Pattern | Use | Example |
|---|---|---|
| `main` | Reviewed, merged work only | — |
| `prd-XX/<topic>` | All work for one PRD | `prd-00/governance`, `prd-01/monorepo`, `prd-03/runner` |
| `fix/prd-XX-<topic>` | A fix to an already-closed PRD | `fix/prd-02-cursor-order` |

Branch names are lowercase, kebab-case, and always carry the two-digit PRD number.

## Commits

Conventional Commits with the PRD as scope:

```
<type>(prd-XX): <imperative summary, ≤ 72 chars>

<optional body: why, trade-offs, test IDs touched>
```

- **Types:** `feat`, `fix`, `test`, `docs`, `refactor`, `chore`, `ci`, `build`, `perf`.
- **Examples:**
  - `feat(prd-03): add browser worker status reporting`
  - `test(prd-02): cover concurrent idempotent create (API-004)`
  - `docs(prd-00): add ADR-004 dispatch ownership`
- Keep commits small and reviewable; one logical change each.
- Reference test IDs from `docs/SYSTEM_TEST_MATRIX.md` in the body when a commit adds or changes a test.
- Never commit `.env`, credentials, real tokens or generated artifacts.
  - **One exception:** the contract files `api/openapi.yaml` and `packages/contracts/src/api.ts` are generated and committed on purpose (PRD-02 FR-9). Any change to an API DTO or endpoint runs `make contracts` and commits both in the same commit; CI's `make contracts-check` fails otherwise.

## Pull requests

- Use `.github/pull_request_template.md`; every checkbox is either ticked or explained.
- The PR description links `docs/evidence/PRD-XX.md`.
- Merge with a merge commit or squash — but keep the PRD scope in the final message.

## Closing a PRD

1. Every task checked with real implementation: `python3 tools/progress.py check PRD-XX Tn`.
2. `docs/evidence/PRD-XX.md` written from `docs/evidence/TEMPLATE.md` with real commands and output.
3. The developer reviews the evidence and approves.
4. Only then: `python3 tools/progress.py done PRD-XX --evidence docs/evidence/PRD-XX.md` and `python3 tools/progress.py report`.

## ADRs

New architectural decisions go in `docs/decisions/ADR-NNN-<slug>.md` using `ADR_TEMPLATE.md`, and are added to `docs/decisions/README.md`. ARCHITECTURE §12 lists what requires one.

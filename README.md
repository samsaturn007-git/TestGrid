# TestGrid — Codex-ready engineering project kit (v2)

**Mission:** design, build, test and present a reliability-first, observable cross-browser test orchestration platform as a flagship SDE I portfolio project.

- **Status:** PRD-00 done; PRD-01 (monorepo, local environment, demo shop) in progress. Live progress: `python3 tools/progress.py status`.
- **v2 (2026-11-19):** every PRD is deepened with functional requirements, API, data and UI specs, and test-ID-linked acceptance criteria. Four showcase features are folded in: run comparison, flaky quarantine, smart sharding and an observability stack. See `CHANGES_v2.md`.
- **Scope:** a solo developer, strong in web development and a beginner in DSA, working part-time alongside interview preparation.

## Run it locally

```bash
make doctor && make install && make up
pnpm --filter worker exec playwright install chromium
make smoke
```

Full setup (macOS, Linux, Windows WSL2), fixture modes and troubleshooting: `docs/SETUP.md`.

| Folder | Contents |
|---|---|
| `api/` | Spring Boot 4 / Java 21 API (Maven wrapper). PRD-01: `/healthz` only |
| `web/` | React + Vite dashboard placeholder (real UI in PRD-05) |
| `demo-shop/` | Owned demo shop: React SPA + Express API with per-session fixture modes |
| `worker/` | Playwright `checkout-smoke` spec (orchestrated worker in PRD-03) |
| `cli/`, `chaos/`, `packages/contracts/` | Placeholders for PRD-08, PRD-10 and PRD-02/03 |
| `infra/compose/` | Docker Compose for the local environment |
| `scripts/` | `doctor.sh`, `health.sh`, `mode-matrix.sh` |

## Where to begin

1. Read `MASTER_PRD.md`: the why, what, scope, milestones and success criteria.
2. Read `AGENTS.md`: the rules Codex must follow every time.
3. Read `docs/API_AND_DATA_CONTRACTS.md` (the **vocabulary source of truth**), `docs/ARCHITECTURE.md` and `docs/SECURITY_AND_TEST_STRATEGY.md`.
4. Skim `docs/SYSTEM_TEST_MATRIX.md`. Every acceptance criterion points to a test ID there.
5. Open `prds/PRD-00-governance.md`. **Give Codex one PRD at a time**, plus the context files above.
6. Follow `CODEX_PLAYBOOK.md` for copy-paste prompts, and use `CODEX_FIRST_PROMPT.md` to start.
7. Track work with `python3 tools/progress.py status`.

## Package map

| Location | Why it exists |
|---|---|
| `MASTER_PRD.md` | Product vision, non-goals, scope, decisions, gates, effort |
| `prds/PRD-00 … PRD-15` | 14 sequential core PRDs (00–13) and 2 optional PRDs (14–15) |
| `AGENTS.md` | Standing agent instructions to copy into the code repository |
| `CODEX_PLAYBOOK.md` / `CODEX_FIRST_PROMPT.md` | Prompts to start, implement, review, close and resume |
| `docs/API_AND_DATA_CONTRACTS.md` | Status enums, tables, endpoints, events, problem types, AMQP, manifest, CLI |
| `docs/ARCHITECTURE.md` | Components, stages, dispatch ownership, lease protocol, invariants I1–I9, sharding, observability |
| `docs/SECURITY_AND_TEST_STRATEGY.md` | Threat model, fixture ground truth, test pyramid, CI gates by PRD |
| `docs/SYSTEM_TEST_MATRIX.md` | 50+ planned test IDs and the benchmark CSV schema |
| `docs/RESEARCH_AND_DIFFERENTIATION.md` | BrowserStack and open-source comparison, where TestGrid stands out |
| `docs/PORTFOLIO_PLAYBOOK.md` | README outline, demo script, benchmark protocol, resume templates, interview questions |
| `docs/PROJECT_BOARD_AND_PROGRESS.md` | Board setup, weekly check-in, DSA tie-ins |
| `docs/decisions/ADR_TEMPLATE.md`, `docs/evidence/TEMPLATE.md` | ADR and evidence formats |
| `progress.json`, `tools/progress.py`, `PROGRESS.md`, `BACKLOG.csv` | Offline tracker, generated dashboard and importable backlog |
| `CHANGES_v2.md` | What changed from v1 and why |

## Quick progress commands

```bash
python3 tools/progress.py lint      # tracker matches PRD task lists
python3 tools/progress.py status
python3 tools/progress.py start PRD-00
python3 tools/progress.py check PRD-00 T1
python3 tools/progress.py done PRD-00 --evidence docs/evidence/PRD-00.md
python3 tools/progress.py report
```

**What `done` checks:**
- every task is checked and every prerequisite is done;
- the evidence file is under `docs/evidence/`, mentions every AC in the PRD, has no template placeholders left, and states `**Outcome:** pass`.

**What it can't check:** whether the tests really passed. The human reviewer verifies the claims.

## Effort

- Core scope (PRDs 00–13) is about **180–270 hours**: roughly 6–9 months at 7 h/week, or 4–6 months at 12 h/week. These are planning ranges, not promises.
- **Portfolio-ready at PRD-09.** PRDs 10–12 (chaos, scheduling and sharding, security and observability) are what make it a standout.

## Work philosophy

- Build the smallest working vertical slice before adding distributed complexity.
- Measure rather than guess. No made-up benchmarks or resume numbers.
- No arbitrary user scripts or unrestricted URLs.
- Never store BrowserStack or GitHub secrets in code or artifacts.

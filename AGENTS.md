# AGENTS.md — Instructions for Codex working on TestGrid

These are standing instructions. Read `MASTER_PRD.md`, the active numbered PRD, `docs/ARCHITECTURE.md`, `docs/API_AND_DATA_CONTRACTS.md` and `docs/SECURITY_AND_TEST_STRATEGY.md` before modifying files.

## Scope and workflow

1. **One active PRD only.** Never implement features from future PRDs without user approval. Outline file-level changes before coding.
2. Use a short branch/commit convention: `prd-03/runner`, commit messages like `feat(prd-03): add browser worker status reporting`.
3. Start by inspecting existing code and tests. Do not overwrite user work or rename files unnecessarily.
4. Implement the smallest passing vertical slice, then test it, then refactor. Never replace real behavior with UI mock status except where the PRD explicitly allows seeded demo views.
5. Keep contracts consistent: if API or DB changes, update contract docs, migrations, tests and generated frontend types.
6. Use readable code, avoid clever abstractions. No boilerplate or obvious comments; add a short, human-sounding comment only where a concurrency, security or algorithm decision is non-obvious (with time/space complexity for algorithms). Larger reasoning goes in ADRs.
7. Never claim tests passed if they weren't executed; quote commands and actual results. If missing environment or credentials, distinguish mocked from live tests.
8. At PRD end, create `docs/evidence/PRD-XX.md` from template; enumerate acceptance criteria, commands, outputs, limitations and commit IDs.
9. After the human reviewer confirms evidence, update `progress.json` via `tools/progress.py`; don't mark a PRD `done` just because code compiles.
10. Do not use real BrowserStack secrets or paid resources without explicit permission. Do not deploy public execution endpoints by default.

## Engineering standards

- API Java 21/Spring Boot (Maven wrapper); worker, CLI and chaos in TypeScript/Node with Playwright; web React TypeScript; pnpm workspace with one lockfile; DB PostgreSQL + Flyway; RabbitMQ once PRD-04 begins; OpenTelemetry/Prometheus/Grafana/Jaeger in PRD-12 (Compose profile `obs`).
- `docs/API_AND_DATA_CONTRACTS.md` is the vocabulary source of truth: use its status enums, problem types, event names and table names exactly. Never invent a new status spelling.
- Reference test IDs from `docs/SYSTEM_TEST_MATRIX.md` in test names and evidence files.
- Static types and validated input boundaries. Prefer dependency injection; test services without real network when possible.
- Errors propagate as structured response; no generic catch-and-return-success.
- Keep tests deterministic, synthetic fixtures resettable and browser engine versions pinned.
- Use transaction boundaries and idempotency for state mutations; database is source of truth.
- Queue delivery is at-least-once; distinguish DB exactly-once terminal transition from actual external test execution.
- Use secure artifact storage and redaction; tests never receive arbitrary unchecked shell or target URLs.
- Accessible UI, empty/loading/error states, and no hardcoded fake metrics presented as real.
- Enforce invariants in the database where possible (unique/partial indexes, conditional updates), then test them; use DB `now()` for leases, never worker clocks.
- Never pin a library or image version from memory: verify against the official source and record it in `docs/VERSIONS.md`.

## Required interaction pattern

At beginning of each PRD: summarize objective, existing prerequisites, planned files, key risks, verification commands, and **stop for approval only if a real blocker exists**. Otherwise proceed.

At end: write a PRD completion report with: completed tasks, changed files, tests run and results, remaining limitations, threat check, evidence file path, next PRD. Include an explain-back quiz: ask the developer to explain the three most important engineering decisions in their own words.

## Safety guardrails

No arbitrary user code/script uploads, shell injection, unrestricted internet target URLs, Docker socket mounts, leaked cloud access keys, or unauthenticated publicly exposed worker endpoints. Never silently widen the threat surface “for convenience”.

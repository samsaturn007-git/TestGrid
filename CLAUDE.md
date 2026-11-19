# CLAUDE.md — start here

TestGrid is a reliability-first cross-browser test orchestration platform, built **one PRD at a time**. This file is loaded automatically. Read it fully before doing anything else.

## 1. Read before modifying anything

1. `AGENTS.md`: the standing rules. Follow them exactly; they override your defaults.
2. `progress.json` (or `python3 tools/progress.py status`): which PRD is active. **Work only on that PRD.**
3. The active PRD file in `prds/`, plus `MASTER_PRD.md`, `docs/ARCHITECTURE.md`, `docs/API_AND_DATA_CONTRACTS.md` (vocabulary source of truth), `docs/SECURITY_AND_TEST_STRATEGY.md` and `docs/SYSTEM_TEST_MATRIX.md`.
4. `docs/VERSIONS.md`: the pinned versions and this machine's setup. Never pin a version from memory.
5. `docs/decisions/` (ADR-001…004 are accepted) and the latest `docs/evidence/PRD-XX.md`.

Then inspect the actual repository and `git log` before planning. Reconcile the documented progress with the real files.

## 2. Workflow rules (short form of AGENTS.md)

- **One active PRD.** Nothing from a later PRD without the developer's approval.
- Start each PRD by summarizing the objective, prerequisites, planned files, risks and verification commands. Stop only for a real blocker.
- **Branch:** `prd-XX/<topic>`. **Commits:** `type(prd-XX): summary` (see `CONTRIBUTING.md`). Keep commits small.
- Use the exact spellings in contracts §2 / §6 for statuses, problem types and events. `bash tools/vocab_check.sh` must stay green.
- **Never claim a test passed unless you ran it.** Quote the real command, output and exit code. Label mocked vs live tests.
- At PRD end, write `docs/evidence/PRD-XX.md` from `docs/evidence/TEMPLATE.md`, with an explain-back quiz.
- **Never run `tools/progress.py done`** until the developer has reviewed the evidence and said so. You may run `check PRD-XX Tn` for tasks that are genuinely implemented.
- No secrets in the repo, logs or artifacts. Bind ports to `127.0.0.1`. No client-supplied URLs, code, regexes or Playwright flags (ADR-003).

## 3. This machine (Apple M4, macOS, 16 GB RAM): known gotchas

- **Java:** the default `java` is 26, but this repo needs **JDK 21**. Before any Maven or `./mvnw` command:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  (the PRD-01 Makefile should set this itself).
- **Docker:** the engine is **OrbStack** (context `orbstack`, VM limit ≈ 8 GiB). If `docker info` fails, ask the developer to open OrbStack. Don't install another engine.
- **pnpm 12.21.0 runs through Corepack 0.36** (`/opt/homebrew/bin/pnpm` is the Corepack shim). Pin it with `"packageManager": "pnpm@12.21.0"`. If you see `Cannot find module …/corepack/v1/pnpm/12.21.0/bin/pnpm.cjs`, delete `~/.cache/node/corepack/v1/pnpm/12.21.0` and retry.
- **Node 26.13.1** (Homebrew) comes first on `PATH`. An old nvm Node 20 is also on `PATH`; don't rely on it.
- **GNU Make is 3.81:** avoid Make-4-only features.
- **Shell `grep` may be an interactive alias** for a different tool. In scripts, call `/usr/bin/grep` or run them with `bash`.

## 4. Repository and remotes

- Remote: `origin` → https://github.com/samsaturn007-git/TestGrid (public). Default branch `main`.
- Only push when the developer asks. Never force-push `main`.
- PRD-01 AC-5 needs a green GitHub Actions run. CI must use `permissions: contents: read` and actions pinned to commit SHAs.

## 5. Useful commands

```bash
python3 tools/progress.py status        # which PRD is active
python3 tools/progress.py lint          # tracker matches PRD files
python3 tools/progress.py check PRD-XX T1
bash tools/vocab_check.sh               # canonical vocabulary check
```

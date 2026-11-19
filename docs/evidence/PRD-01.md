# Evidence — PRD-01

**PRD:** `prds/PRD-01-monorepo-and-fixture.md`  
**Branch / commits:** `prd-01/monorepo` — `27976d9` (T1), `d8ed0ae` (T2+T3), `8df4307` (T4), `47bbbb3` (T5), `089d4b4` (T6), `7b5b8a3` (T7), `3e5296e` (mode-matrix fix), `e04b519` (evidence); PR https://github.com/samsaturn007-git/TestGrid/pull/1  
**Reviewer:** developer — review and explain-back pending  
**Date:** 2026-11-19 (UTC)  
**Outcome:** pass. All five ACs have observable proof; closing with `tools/progress.py done` still waits on the developer's review and explain-back answers.

All commands below ran on the reference machine (Apple M4, 16 GiB, macOS 27.2.1, OrbStack / Docker 29.8.2, Compose 5.5.0, Node 26.13.1, pnpm 12.21.0, JDK 21.0.13.1, Playwright 1.65.1 with Chromium Headless Shell 159.0.8154.6). Every test is **live** (real Express server, real Chromium, real containers) unless marked otherwise. No paid service and no credential was used.

## Acceptance criteria with evidence

| Criterion | Pass? | Test ID(s) | Proof (file, test and observable behaviour) |
|---|---|---|---|
| AC-1 | Yes (local) | — | Clean clone into an empty directory, then `make install && make up && make smoke`: all four services healthy, 6/6 passed. Output in "Clean clone" below. |
| AC-2 | Yes | — | `scripts/mode-matrix.sh` (`make modes`): `make reset`, then every mode 3× with `/__fixture/reset` before each run. `PASS` 6/6 passed. `PRODUCT_DEFECT` fails only `@cart-total` with `Expected: "$80.99" \| Received: "$82.99"` on all 3 runs. `SELECTOR_DRIFT` fails `@checkout-happy-path` and `@card-declined` with `TimeoutError: locator.click: Timeout 5000ms exceeded.`, a locator error, not an assertion. Every mode was `identical x3`. Output in "Mode matrix" below. |
| AC-3 | Yes | — | Server level: `demo-shop/test/fixtures.test.ts` › "AC-3 fixture session isolation" runs 10 PASS + 10 PRODUCT_DEFECT sessions with interleaved concurrent requests (every PASS total `8099`, every defect total `8299`), plus a hanging TIMEOUT session that doesn't block a PASS order. Browser level: `worker/fixture-checks/isolation.spec.ts` (`make fixture-checks`) runs 4 concurrent Chromium contexts (2× PRODUCT_DEFECT → `$82.99`, 2× PASS → `$80.99`). |
| AC-4 | Yes | — | No paid service or credential: `.env.example` holds fake values only; nothing calls an external host. Pinned: exact npm versions plus `pnpm-lock.yaml`; image tags `postgres:18.7-bookworm`, `eclipse-temurin:21.0.13_9-{jdk,jre}-noble`, `node:26.13.1-bookworm-slim`, `nginxinc/nginx-unprivileged:1.30.6-alpine3.24`; Maven 3.9.16 via wrapper 3.3.4; CI actions pinned to SHAs (`docs/VERSIONS.md` §1.1). Fixtures disabled → 404: unit test "AC-4 fixtures disabled" (4 routes → 404, shop still works), **and** the container restarted with `DEMO_SHOP_FIXTURES=disabled` answers 404 on all `/__fixture/*` routes (output below). |
| AC-5 | Yes | — | GitHub Actions run https://github.com/samsaturn007-git/TestGrid/actions/runs/44576078151 (PR #1, `pull_request` on `e04b519`), conclusion **success** on fresh `ubuntu-24.04` runners: job *check + unit tests* (make check, make test, vocab check) and job *compose up + Chromium smoke* (make up, make smoke, fixture isolation, fixtures-disabled 404). Workflow: `permissions: contents: read`, SHA-pinned actions, no secrets. Log excerpt below. |

## Tasks completed

| Task | Commit(s) | Notes |
|---|---|---|
| T1 | `27976d9` | pnpm 12.21.0 workspace (`demo-shop`, `web`, `worker`; `cli`, `chaos`, `packages/contracts` are README-only), one lockfile. ESLint 10 with type-checked rules, Prettier, `tsc --noEmit` per package. `api/` from start.spring.io (Boot 4.1.2, Java 21), Maven wrapper pinned to 3.9.16, Spotless + google-java-format 2.0.0, `GET /healthz`. |
| T2 | `d8ed0ae` | Demo shop: 6 products, detail, cart (add/remove/quantity), 8 % tax, `SAVE10`, checkout, confirmation with order number, `data-testid` on every element a test touches, fake payment (`…0002` declined, only the last 4 digits kept). |
| T3 | `d8ed0ae` | `POST /__fixture/session`, `POST /__fixture/reset`; the five modes; gated by `DEMO_SHOP_FIXTURES=enabled` **and** a loopback/private source address. (T2 and T3 share `app.ts`, so they're one commit that builds on its own.) |
| T4 | `8df4307` | `worker/suites/checkout-smoke.spec.ts` with the 6 tags; `pnpm --filter worker exec playwright test --project=chromium`. |
| T5 | `47bbbb3` | `infra/compose/compose.yaml`, Dockerfiles, `.env.example`, `Makefile`, `scripts/health.sh`. |
| T6 | `089d4b4`, `3e5296e` | `scripts/doctor.sh`, `scripts/mode-matrix.sh`, `scripts/summarize-playwright.mjs`, `docs/SETUP.md` (macOS, Linux, WSL2), README quick start. |
| T7 | `7b5b8a3` | `.github/workflows/ci.yml`; green on its first run (AC-5). |

## Changed files

- `package.json`, `pnpm-workspace.yaml`, `pnpm-lock.yaml`, `tsconfig.base.json`, `eslint.config.mjs`, `.prettierrc.json`, `.prettierignore`, `.editorconfig`, `.nvmrc`, `.tool-versions`: workspace and tooling (FR-1, FR-3).
- `api/` (`pom.xml`, `mvnw*`, `.mvn/wrapper/maven-wrapper.properties`, `HealthController`, `HealthControllerTest`, `application.properties`, `Dockerfile`): API skeleton, health only.
- `demo-shop/src/server/*`: Express API, sessions, pricing, PRNG, private-address check, config. `demo-shop/src/client/*`: React SPA. `demo-shop/test/*`: node:test suites.
- `worker/`: Playwright config, smoke spec, fixture helpers, isolation check, unit tests.
- `web/`: Vite + React placeholder served by nginx-unprivileged.
- `infra/compose/compose.yaml`, `.env.example`, `.dockerignore`, `Makefile`: local environment (FR-2, FR-8).
- `scripts/doctor.sh`, `scripts/health.sh`, `scripts/mode-matrix.sh`, `scripts/summarize-playwright.mjs`: readiness and verification (T6).
- `.github/workflows/ci.yml`: CI (FR-9).
- `docs/SETUP.md`, `README.md`, `docs/VERSIONS.md` (§1.1 tooling pins and action SHAs).
- `cli/README.md`, `chaos/README.md`, `packages/contracts/README.md`, `infra/compose/README.md`: placeholders.

## Design notes (for review)

- **Isolation by construction.** Every piece of state a session can change (cart, coupon, orders, PRNG state) lives on its own session object, keyed by a 128-bit random cookie. A fixture call always creates a *new* session, so two browser contexts can't share a mode. The store is capped at 5,000 sessions (oldest evicted).
- **AUTOMATION_FLAKE determinism.** Each session owns a `mulberry32(seed)` PRNG, and each placed order draws once (late if `< 0.5`). The spec seeds each Playwright try with `FIXTURE_SEED + retry`, so a seed fixes the whole late/on-time pattern. Measured: seed 0 → late then on time (`flaky`), seed 7 → late twice (`failed`), seed 42 → on time (`passed`).
- **Each mode fails with its intended error type.** The timeouts are set against each other: flake delay 8 s > `expect` 5 s gives an assertion failure; `actionTimeout` 5 s on a missing test ID gives a locator error; order hang 120 s > test timeout 20 s, with `waitForResponse({timeout: 0})`, gives a test timeout. That matches the SECURITY fixture table (`ASSERTION`, `SELECTOR_NOT_FOUND`, `TEST_TIMEOUT`), which PRD-07 will classify.
- **Fixture routes are hidden twice.** The router is only mounted when `DEMO_SHOP_FIXTURES` is exactly `enabled` (any other value fails startup through the zod enum), and it answers 404 to non-private source IPs.

## Actual executed commands and outputs

### `make check` / `make test`

```text
$ make check > check.log 2>&1; echo "make check exit=$?"
make check exit=0          # prettier --check, eslint --max-warnings=0, tsc --noEmit ×3, mvnw spotless:check compile
$ make test > test.log 2>&1; echo "make test exit=$?"
make test exit=0
worker test:    ℹ tests 5   ℹ pass 5   ℹ fail 0
demo-shop test: ℹ tests 42  ℹ pass 42  ℹ fail 0
api (surefire): HealthControllerTest tests="2" errors="0" skipped="0" failures="0"
$ bash tools/vocab_check.sh
Canonical enum values (contracts §2.1): 52
Non-canonical spellings: none
vocab_check OK
$ python3 tools/progress.py lint
lint OK: tracker matches PRD files
```

### `make up` timing (NFR: under 3 minutes after images are pulled)

```text
# first build: base images pulled, no build cache, Maven and pnpm dependencies downloaded
$ time make up
all services healthy in 149s
health:
  api        OK   http://127.0.0.1:8080/healthz {"status":"UP"}
  web        OK   http://127.0.0.1:3000/healthz {"status":"ok"}
  demo-shop  OK   http://127.0.0.1:4173/healthz {"status":"ok","fixtures":"enabled"}
make up  0.69s user 0.61s system 0% cpu 2:28.59 total

# `make reset` (down -v, then up with a warm build cache), at the start of the mode matrix
all services healthy in 18s
```

### AC-1: clean clone

```text
$ git clone --branch prd-01/monorepo <repo> <empty scratch dir>/clean-clone && cd clean-clone
$ git log --oneline -1
3e5296e fix(prd-01): write mode-matrix output outside Playwright's test-results
$ time (make install && make up && make smoke); echo "clean-clone exit=$?"
Done in 419ms using pnpm v12.21.0
created .env from .env.example (fake local values)
all services healthy in 20s
health:
  api        OK   http://127.0.0.1:8080/healthz {"status":"UP"}
  web        OK   http://127.0.0.1:3000/healthz {"status":"ok"}
  demo-shop  OK   http://127.0.0.1:4173/healthz {"status":"ok","fixtures":"enabled"}
  6 passed (1.6s)
( make install && make up && make smoke; )  3.69s user 1.25s system 20% cpu 23.827 total
clean-clone exit=0
```

Caveat: the clone was on the same machine, so the pnpm store, the Playwright browser cache and the Docker build cache were warm. This proves the committed files alone are enough. It does not re-measure a cold build (149 s above). The fresh-runner proof is AC-5.

### Mode matrix (AC-2, NFR "identical results after `make reset`")

```text
$ bash scripts/mode-matrix.sh      # make reset, then each mode/seed 3× with /__fixture/reset before each run
all services healthy in 18s
mode-matrix exit=0
```

Full output: `docs/evidence/raw/prd-01-mode-matrix.txt`. Summary (columns: case, Playwright outcome, status per try, error):

```text
=== PASS seed=42: identical x3                 stats: expected=6 unexpected=0 flaky=0
=== PRODUCT_DEFECT seed=42: identical x3
@cart-total            unexpected failed>failed      Error: expect(locator).toHaveText(expected) failed | Expected: "$80.99" | Received: "$82.99"
                                               stats: expected=5 unexpected=1 flaky=0
=== SELECTOR_DRIFT seed=42: identical x3
@card-declined         unexpected failed>failed      TimeoutError: locator.click: Timeout 5000ms exceeded.
@checkout-happy-path   unexpected failed>failed      TimeoutError: locator.click: Timeout 5000ms exceeded.
=== TIMEOUT seed=42: identical x3
@card-declined         unexpected timedOut>timedOut  Test timeout of 20000ms exceeded.
@checkout-happy-path   unexpected timedOut>timedOut  Test timeout of 20000ms exceeded.
=== AUTOMATION_FLAKE seed=0: identical x3
@checkout-happy-path   flaky      failed>passed      Error: expect(locator).toBeVisible() failed | Expected: visible
=== AUTOMATION_FLAKE seed=7: identical x3
@checkout-happy-path   unexpected failed>failed      Error: expect(locator).toBeVisible() failed | Expected: visible
=== AUTOMATION_FLAKE seed=42: identical x3      stats: expected=6 unexpected=0 flaky=0
```

All cases not listed passed in every run. The matrix ran twice today with identical verdicts (the first run's results matched; only its final copy step failed, fixed in `3e5296e`).

### `make smoke` (PASS) against the containers

```text
$ make smoke
  6 passed (1.9s)
$ make fixture-checks
  ✓  1 [fixture-checks] › fixture-checks/isolation.spec.ts:26:1 › concurrent PASS and PRODUCT_DEFECT contexts do not affect each other @fixture-isolation (522ms)
  1 passed (891ms)
```

### AC-5: GitHub Actions run 44576078151 (fresh runner)

```text
$ gh run view 44576078151 --json status,conclusion,jobs
{"conclusion":"success","jobs":[{"conclusion":"success","name":"compose up + Chromium smoke"},{"conclusion":"success","name":"check + unit tests"}],"status":"completed"}

check + unit tests (17:20:20Z → 17:21:09Z)
  worker test:    ℹ tests 5   ℹ pass 5   ℹ fail 0
  demo-shop test: ℹ tests 42  ℹ pass 42  ℹ fail 0
  vocab_check OK
compose up + Chromium smoke (17:20:20Z → 17:22:32Z)
  all services healthy in 78s          # cold build on a fresh runner, including image pulls
  api        OK   http://127.0.0.1:8080/healthz {"status":"UP"}
  web        OK   http://127.0.0.1:3000/healthz {"status":"ok"}
  demo-shop  OK   http://127.0.0.1:4173/healthz {"status":"ok","fixtures":"enabled"}
  6 passed (2.9s)                      # make smoke, Chromium
  1 passed (1.8s)                      # fixture isolation (AC-3)
  POST /__fixture/session with fixtures disabled -> 404
```

### AC-4: container with fixtures disabled

```text
$ DEMO_SHOP_FIXTURES=disabled docker compose -f infra/compose/compose.yaml --env-file .env up -d --wait demo-shop
healthz: {"status":"ok","fixtures":"disabled"}
POST /__fixture/session -> 404
POST /__fixture/reset -> 404
GET /__fixture/slow?class=S -> 404
GET /__fixture -> 404
--- fixtures enabled again:
healthz: {"status":"ok","fixtures":"enabled"}
POST /__fixture/session -> 201
POST /__fixture/session -> 400      # extra "url" field rejected (strict schema)

# smoke spec against the disabled shop fails fast and says why:
Error: fixture session must be created (is DEMO_SHOP_FIXTURES=enabled?)
Expected: 201
Received: 404
```

## Negative / failure tests

- **PRODUCT_DEFECT, SELECTOR_DRIFT, TIMEOUT, AUTOMATION_FLAKE:** injected through `FIXTURE_MODE` / `FIXTURE_SEED` → `POST /__fixture/session`. Observed failures are listed in the mode matrix; after `/__fixture/reset` the next run starts clean and repeats identically.
- **Fixtures disabled:** 404 in unit tests and in the container; the shop keeps working.
- **Hostile input to the shop:** unknown JSON fields → 400, malformed JSON → 400, body over 16 KB → 413 handler, unknown coupon → 422, unknown fixture mode (`BROWSER_LAUNCH`) / negative seed / extra `url` field → 400, card number that isn't 12–19 digits → 400.
- **Worker env:** `parseFixtureEnv` rejects non-canonical modes (`pass`, `TIMED_OUT`, `BROWSER_LAUNCH`), malformed seeds, and non-allowlisted or non-bare shop origins (`http://169.254.169.254`, `file:///etc/passwd`, `http://user:pw@localhost:4173`, …).

## Security and threat check

- **Input restrictions:** every shop write endpoint uses a strict zod schema (unknown fields rejected), JSON body ≤ 16 KB, quantities 1–10, string lengths bounded. Fixture mode must be a `FixtureMode` value; the shop never accepts a URL, code or arbitrary duration (contracts §13).
- **Secrets:** none exist. `.env.example` holds fake placeholders; `.env` is git-ignored and in `.dockerignore`. The fake card number is never stored or returned beyond its last four digits (asserted in `fixtures.test.ts`). The shop logs nothing from request bodies.
- **Network surface:** every published port binds to `127.0.0.1`. Postgres is on the `internal: true` network only. Containers run non-root (`node`, uid 1000, nginx-unprivileged) with `no-new-privileges`; api/web/demo-shop also `cap_drop: ALL`, and web/demo-shop have read-only root filesystems. No Docker socket mount. CI token is `contents: read`, `persist-credentials: false`, no secrets.
- **New attack surface and mitigation:** demo-shop is published on `127.0.0.1:4173` and attached to `edge` so the host-side smoke can reach it (PRD-01 FR-7 runs Playwright directly). Mitigations: loopback-only bind; fixture routes answer private source addresses only. **PRD-03 removes the port and the `edge` attachment** once the worker runs inside `internal` (ARCHITECTURE §9, CONFLICT_REGISTER N4).
- **Canary scan:** not applicable yet (no secrets or artifacts exist); SEC-002 starts in PRD-06.

## Skipped or mocked tests

- Nothing is mocked. The node:test suites start the real Express app on an ephemeral port; Playwright drives real Chromium. Firefox/WebKit are out of scope until PRD-03 (E2E-002).

## Screenshots / demo / raw data links

- Mode-matrix output: `docs/evidence/raw/prd-01-mode-matrix.txt` (copied from `tmp/mode-matrix/mode-matrix.txt`).
- Playwright HTML report and traces of failing modes: `worker/playwright-report/` (generated locally, git-ignored).

## Known limitations and deferred improvements

- `FixtureMode` is declared twice (`demo-shop/src/server/fixture-mode.ts`, `worker/suites/support/fixture-env.ts`). It moves to `packages/contracts` with the manifest schema in PRD-03. The vocab check keeps both spellings canonical.
- The demo-shop host port and `edge` attachment exist only for PRD-01's host-side smoke (see threat check).
- The API container waits for Postgres but doesn't connect yet; readiness on DB arrives with PRD-02.
- `/__fixture/slow` (contracts §13) belongs to PRD-11 and returns 404 today.
- The Maven wrapper has no `distributionSha256Sum`; digest pinning for images and the wrapper belongs to PRD-12 supply-chain hardening.
- `TypeScript` is pinned to 6.0.3, not `latest` 7.0.2, because typescript-eslint 8.73 doesn't support 7 yet (`docs/VERSIONS.md` §1.1).
- `make up` timing is from one cold run and one warm run, not a distribution.

## Explain-back review (developer's own words)

*To be answered by the developer. AGENTS.md requires this before `tools/progress.py done`.*

1. The three most important engineering decisions in this PRD, and why. Suggested prompts:
   - Why does a fixture call create a *new* session instead of changing the current one, and what would break under concurrent runs if it changed a shared global mode?
   - How do `FIXTURE_SEED + retry` and one PRNG draw per order make AUTOMATION_FLAKE deterministic, and why is "seed 0 → flaky" a stable fact rather than luck?
   - Why do the timeouts (5 s expect, 5 s action, 8 s flake delay, 20 s test, 120 s hang) have to be ordered this way for each mode to produce its intended error class?
2. What happens if the main operation fails halfway through? (For example, the browser context closes while the TIMEOUT order request is held open: what cleans up the timer?)
3. What would you change for production? (For example, a real session store, removing the published shop port, digest-pinned images.)

# PRD-01 — Monorepo, reproducible local environment, and owned demo shop

**Priority:** P0 | **Estimated effort:** 10–16 h | **Dependencies:** PRD-00 | **Gate:** G1 | **Status:** not started

## Objective
A clean clone starts with one command. It brings up a realistic demo shop you own, which can be switched into controlled failure modes so every later diagnosis feature has known, labelled ground truth.

## User stories
- As a developer, I run `make up` and get the API skeleton, web skeleton and demo shop healthy in Docker.
- As a test author, I can make the shop fail in a specific, repeatable way for one browser session without affecting any other session.

## Interview signal
Monorepo tooling, reproducible builds, deterministic test fixtures, CI fundamentals.

## Functional requirements
1. **FR-1 Layout**
   - Folders: `api/` (Maven wrapper, Spring Boot), `web/`, `worker/`, `demo-shop/`, `cli/`, `chaos/`, `packages/contracts/`, `infra/compose/`, `scripts/`, `docs/`.
   - Node packages share a pnpm workspace with one committed lockfile. Empty packages hold placeholder `README.md` files with no code.
2. **FR-2 Root commands (`Makefile`)**
   - `make up` / `down` / `reset` / `logs`
   - `make check` (format, lint, typecheck)
   - `make test`
   - `make smoke` (curl health checks plus the Playwright smoke run directly against demo-shop)
3. **FR-3 Tooling**
   - TypeScript: ESLint and Prettier.
   - Java: Spotless.
   - `tsc --noEmit` for every TypeScript package; `./mvnw verify` for the API.
   - `.editorconfig` and `.nvmrc` / `.tool-versions` matching `docs/VERSIONS.md`.
4. **FR-4 Demo shop**
   - Pages: catalog with 6 seeded products, product detail, cart (add, remove, quantity), cart total with a fixed 8 % tax, coupon `SAVE10`, checkout form, and an order confirmation showing an order number.
   - **Payment is fake and entirely local:** cards ending in `0002` are declined, anything else is approved. No real payment provider and no real card data.
   - Every element a test touches has a stable `data-testid`.
5. **FR-5 Fixture sessions**
   - `POST /__fixture/session {mode, seed}` returns a session cookie. The mode and seed apply only to that browser context, so runs with different modes can execute at the same time.
   - The `/__fixture/*` routes exist only when `DEMO_SHOP_FIXTURES=enabled` and the shop is on the internal network.
6. **FR-6 Fixture modes** (from contracts `FixtureMode`):
   - `PASS`: normal behaviour.
   - `PRODUCT_DEFECT`: the cart total is off by 2.00. A deterministic assertion failure.
   - `AUTOMATION_FLAKE`: the confirmation banner is delayed. A seeded pseudo-random generator decides whether each try is late, so a given seed always produces the same pattern.
   - `SELECTOR_DRIFT`: the checkout button's `data-testid` is renamed. The automation breaks; the product is fine.
   - `TIMEOUT`: the order API never responds within the test timeout.
7. **FR-7 Smoke spec:** a Playwright `checkout-smoke` spec with 6 tagged cases (`@browse-catalog`, `@add-to-cart`, `@cart-total`, `@apply-coupon`, `@checkout-happy-path`, `@card-declined`), run directly with `pnpm --filter worker exec playwright test` (no orchestration yet). It moves into the suite manifest in PRD-03.
8. **FR-8 Compose**
   - Services `postgres`, `api` (health endpoint only), `web` (placeholder page), `demo-shop`.
   - Health checks with `depends_on: condition: service_healthy`; images pinned by version tag.
   - Ports bound to `127.0.0.1` only. `.env.example` contains fake values only.
9. **FR-9 CI** (`.github/workflows/ci.yml`):
   - Triggers on `pull_request` and pushes to `main`. `permissions: contents: read`; actions pinned to commit SHAs.
   - Runs `make check`, the unit tests, and the smoke spec on Chromium.

## Non-functional requirements
- `make up` to all services healthy in under 3 minutes on the reference machine after images are pulled. Measure it and record the result.
- Running the smoke spec twice after `make reset` gives identical results.

## Tasks (in order)
- [ ] **T1** — Initialize monorepo, pnpm workspace, Maven wrapper, lint/format/typecheck and health commands.
- [ ] **T2** — Build the owned demo-shop UI and API with stable test IDs and the fake payment rule.
- [ ] **T3** — Implement fixture sessions and modes PASS, PRODUCT_DEFECT, AUTOMATION_FLAKE, SELECTOR_DRIFT, TIMEOUT.
- [ ] **T4** — Write the checkout-smoke Playwright spec with six tagged cases.
- [ ] **T5** — Create Docker Compose, healthchecks, `.env.example` with safe fake values and the Makefile.
- [ ] **T6** — Add readiness scripts and setup docs for Linux/macOS plus Windows (WSL2) notes.
- [ ] **T7** — Add GitHub CI for format, lint, typecheck, unit and Chromium smoke.

## Acceptance criteria
- [ ] **AC-1:** From a clean clone, the documented `make up && make smoke` succeeds and the evidence shows its output.
- [ ] **AC-2:** `PASS` passes all 6 cases. `PRODUCT_DEFECT` fails `@cart-total` with the same message on 3 repeated runs. `SELECTOR_DRIFT` fails with a selector or locator error, not an assertion.
- [ ] **AC-3:** Two concurrent fixture sessions with different modes don't affect each other (automated test).
- [ ] **AC-4:** No paid services or real credentials are needed, versions are pinned, and `/__fixture/*` returns 404 when fixtures are disabled.
- [ ] **AC-5:** CI passes in a fresh GitHub runner. Link the run.

## Verification
- `make up && make smoke`
- `curl -fsS localhost:<port>/healthz`
- Repeat each mode 3 times after `make reset`
- `DEMO_SHOP_FIXTURES=disabled` negative test
- A CI run link

## Demo moment
Flip the shop between `PASS` and `PRODUCT_DEFECT` and show the cart total change while the test fails on exactly one case.

## Risks
- Docker resource limits.
- Flaky seed behaviour (`AUTOMATION_FLAKE` must be deterministic per seed).
- Credentials leaking into `.env`.

## Out of scope
Queue, dashboard features, the worker orchestration loop, cloud integrations.

## Handoff and definition of done
- Implement **PRD-01 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T7 with tests, then write `docs/evidence/PRD-01.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain the fixture design without AI;
  - the tracker is updated only after human review.

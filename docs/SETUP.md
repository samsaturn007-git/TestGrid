# Local setup (PRD-01)

Everything runs locally. No paid service, cloud account or real credential is needed. Exact versions are in `docs/VERSIONS.md`; `make doctor` checks them for you.

## 1. Prerequisites

| Tool | Version | Check |
|---|---|---|
| Node.js | 26.13.1 (`.nvmrc`, `.tool-versions`) | `node -v` |
| pnpm | 12.21.0 via Corepack 0.36 (`package.json#packageManager`) | `pnpm -v` |
| JDK | 21 (Temurin 21.0.13+9 or any 21.x build) | `$JAVA_HOME/bin/java -version` |
| Docker Engine + Compose v2 | any recent; reference machine: Docker 29.8 / Compose 5.5 | `docker info`, `docker compose version` |
| GNU Make | 3.81 or newer | `make -v` |
| curl, bash | system | — |

Docker needs about **4 GiB of RAM** for the PRD-01 services (budget in `docs/VERSIONS.md` §3) and ~3 GiB of disk for images.

## 2. Quick start

```bash
make doctor          # toolchain vs pins; prints a fix for every FAIL
make install         # pnpm install --frozen-lockfile
make up              # build + start postgres, api, web, demo-shop; waits until all are healthy
pnpm --filter worker exec playwright install chromium   # once per machine
make smoke           # curl health checks + checkout-smoke on Chromium (PASS mode)
```

`make up` creates `.env` from `.env.example` if it is missing. Every value in it is fake and local-only.

| Service | URL (loopback only) | Health |
|---|---|---|
| api | http://127.0.0.1:8080 | `/healthz` |
| web | http://127.0.0.1:3000 | `/healthz` |
| demo-shop | http://127.0.0.1:4173 | `/healthz` |
| postgres | not published (internal network only) | `pg_isready` in its healthcheck |

Other commands: `make help` lists them all. The main ones are `make down`, `make reset` (wipes volumes and starts clean), `make logs`, `make check`, `make test`, `make fixture-checks` and `make modes`.

## 3. Fixture modes

The demo shop's `/__fixture/*` routes exist only when `DEMO_SHOP_FIXTURES=enabled` (the `.env.example` default for local work) **and** the request comes from a loopback or private address. Otherwise they return `404`.

Run the smoke spec in another mode:

```bash
FIXTURE_MODE=PRODUCT_DEFECT make smoke
FIXTURE_MODE=AUTOMATION_FLAKE FIXTURE_SEED=0 make smoke
make modes          # every mode x3 after `make reset`, with a determinism verdict
```

| Mode | What the shop does | Expected smoke result |
|---|---|---|
| `PASS` | normal | 6 passed |
| `PRODUCT_DEFECT` | cart total is 2.00 too high | `@cart-total` fails: expected `$80.99`, received `$82.99` |
| `SELECTOR_DRIFT` | checkout button `data-testid` becomes `cart-checkout-cta` | `@checkout-happy-path` and `@card-declined` fail with `locator.click: Timeout` |
| `TIMEOUT` | `POST /api/orders` holds the request for 120 s | `@checkout-happy-path` and `@card-declined` hit the 20 s test timeout |
| `AUTOMATION_FLAKE` | order confirmation is late (8 s) when the session's seeded PRNG says so | depends on the seed: `0` → flaky (late, then on time on retry), `7` → fails twice, `42` → passes |

Each Playwright try creates a fresh fixture session with seed `FIXTURE_SEED + retry`, so the same seed always yields the same pattern.

## 4. macOS

- Reference machine: Apple M4, macOS 27, OrbStack as the Docker engine (VM limit ≈ 8 GiB). Docker Desktop also works.
- The default `java` may be newer than 21. The Makefile automatically uses Homebrew's `openjdk@21` when `/opt/homebrew/opt/openjdk@21` exists. Otherwise set `JAVA_HOME` yourself:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)
  ```
- macOS ships GNU Make 3.81. The Makefile avoids Make 4 features.
- Corepack is no longer bundled with Node 25+. Install it with `npm install -g corepack@0.36.0 && corepack enable pnpm`. If you see `Cannot find module …/corepack/v1/pnpm/12.21.0/bin/pnpm.cjs`, delete `~/.cache/node/corepack/v1/pnpm/12.21.0` and retry.

## 5. Linux

- Install Docker Engine and the Compose v2 plugin from Docker's packages, and add your user to the `docker` group (log out and back in).
- Install Temurin 21 (Adoptium apt repo) or your distro's `openjdk-21-jdk`, then export `JAVA_HOME`.
- Playwright needs system libraries for Chromium: `pnpm --filter worker exec playwright install --with-deps chromium` (uses `sudo apt-get`).

## 6. Windows (WSL2)

- Use **WSL2 with Ubuntu 24.04** and run every command inside the WSL shell. Native PowerShell/cmd is not supported (the Makefile and scripts are bash).
- Install Docker Desktop with the WSL2 backend and enable integration for your distro, or install Docker Engine inside WSL.
- Clone the repo inside the WSL filesystem (`~/src/...`), not under `/mnt/c`. Bind-mount and file-watching performance on `/mnt/c` is poor, and line endings can break `mvnw` (`.gitattributes` keeps it LF).
- Ports bound to `127.0.0.1` inside WSL are reachable from Windows browsers through WSL's localhost forwarding.
- Then follow the Linux section.

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `docker info` fails | start the engine (OrbStack / Docker Desktop) |
| `make up` times out on `api` | check `make logs`; first build downloads Maven dependencies (~1–2 min) |
| `fixture session must be created` in smoke output | `.env` has `DEMO_SHOP_FIXTURES` other than `enabled`; fix it and `make up` |
| Port already in use | change `API_PORT` / `WEB_PORT` / `DEMO_SHOP_PORT` in `.env` |
| `Executable doesn't exist` from Playwright | `pnpm --filter worker exec playwright install chromium` |

## Worker and end-to-end runs (PRD-03)

`make up` also builds and starts the `worker` container (`mcr.microsoft.com/playwright:v1.65.1-noble`, a ~3.6 GiB image on arm64; the first build downloads it). It claims work from the API's internal port, which is never published, and reaches the demo-shop over the `internal` network.

```bash
make e2e                                    # PASS, PRODUCT_DEFECT, SELECTOR_DRIFT, TIMEOUT, catalog-regression on Chromium
make e2e ENGINES="chromium firefox webkit"  # needs TESTGRID_WORKER_ENGINES=chromium,firefox,webkit in .env, then `make up`
make kill-check                             # kill -9 the worker mid-run: the lease expires visibly, nothing passes
make sigterm-check                          # graceful stop: 20 s grace, then INFRA_ERROR / WORKER_SHUTDOWN
```

Old `.env` files need `TESTGRID_WORKER_TOKEN` (copy the fake value from `.env.example`); Compose refuses to start without it.

**Engine notes (checked on the reference Mac, OrbStack, arm64 image, 2026-11-20)**
- **Firefox needs a writable `HOME`.** The worker's root filesystem is read-only, so `/home/pwuser` is a tmpfs owned by `pwuser` (uid 1001). With a root-owned home, Firefox exits with "Could not find profile folder" and the target is reported as `INFRA_ERROR / BROWSER_LAUNCH` (never as a pass).
- **Browser sandboxes are off or degraded inside the container.** Firefox logs `Sandbox: CanCreateUserNamespace() clone() failure: EPERM` (no user namespaces with `cap_drop: ALL` and `no-new-privileges`), and Playwright starts Chromium with `--no-sandbox` by default. The container (non-root, read-only, no capabilities, internal network, resource limits) is the isolation boundary. Only checked-in suites run against the allowlisted demo-shop (ADR-003).
- **WebKit is Playwright's Linux WebKit build**, not Safari on macOS; rendering and timing can differ from a real Safari.
- **One worker, one slot:** a three-engine run executes its targets one after another. Parallel workers arrive in PRD-04.
- CI runs the Chromium e2e on `ubuntu-24.04` (amd64 image); the three-engine run is local and on demand.

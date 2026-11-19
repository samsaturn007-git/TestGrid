# Pinned versions and resource budget

**Checked on:** 2026-11-19 (UTC), against the official sources linked in each row. Re-check before the PRD that first installs a component; update this file and the commit in the same PR (AGENTS.md: "never pin a library or image version from memory").

## 1. Versions to pin

| Component | Pin | First used | Why this one | Official source (checked 2026-11-19) |
|---|---|---|---|---|
| JDK | **Java 21 LTS** — Eclipse Temurin `21.0.13+9` (latest 21 GA build; a `21.0.13.1+2` respin was published 2026-11-18) | PRD-01 | AGENTS mandates Java 21; latest 21.x patch | [Adoptium API: latest 21](https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=aarch64&image_type=jdk&os=mac) · [Adoptium release versions](https://api.adoptium.net/v3/info/release_versions?release_type=ga&version=%5B21%2C22%29) |
| JDK image (API container) | `eclipse-temurin:21.0.13_9-jre-noble` (build stage: `21.0.13_9-jdk-noble`) | PRD-01 | matches the JDK pin | [Docker Hub eclipse-temurin tags](https://hub.docker.com/_/eclipse-temurin/tags?name=21.0.13) |
| Spring Boot | **4.1.2** (GA, 2026-10-22) | PRD-01 | latest GA; `4.2.0` is only at RC2. Requires Java 17+ (compatible up to 27), Maven 3.6.3+ | [GitHub releases](https://github.com/spring-projects/spring-boot/releases) · [System requirements](https://docs.spring.io/spring-boot/system-requirements.html) |
| Maven (via wrapper) | **Maven 3.9.16**, Maven Wrapper **3.3.4** | PRD-01 | latest 3.9.x GA; Maven 4 is still `4.0.0-rc-7` | [Maven Central: apache-maven metadata](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml) · [maven-wrapper metadata](https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/maven-metadata.xml) |
| Node.js | **26.13.1** (v26 "Lithium", Active LTS since 2026-10-28, maintenance from 2027-10-20, EOL 2029-04-30) | PRD-01 | newest Active LTS; v24 "Krypton" entered maintenance on 2026-10-20 | [nodejs.org dist index](https://nodejs.org/dist/index.json) · [Node release schedule](https://github.com/nodejs/Release/blob/main/schedule.json) |
| Node image (worker/shop builds) | `node:26.13.1-bookworm-slim` | PRD-01 | matches the Node pin | [Docker Hub node tags](https://hub.docker.com/_/node/tags?name=26.13.1) |
| pnpm | **12.21.0** (npm `latest` dist-tag, 2026-11-13; `12.22.0` is tagged `next`) | PRD-01 | latest stable; `engines.node >=18` | [npm registry: pnpm](https://registry.npmjs.org/pnpm) · [GitHub release v12.21.0](https://github.com/pnpm/pnpm/releases/tag/v12.21.0) |
| Playwright (`@playwright/test`) | **1.65.1** (2026-11-13) — bundles Chromium 159.0.8154.6, Firefox 159.0, WebKit 27.2 | PRD-01 | latest stable; supports Node 22/24/26 | [GitHub release v1.65.1](https://github.com/microsoft/playwright/releases/tag/v1.65.1) · [npm registry](https://registry.npmjs.org/@playwright/test) · [System requirements](https://playwright.dev/docs/intro#system-requirements) |
| Playwright Docker image | `mcr.microsoft.com/playwright:v1.65.1-noble` (multi-arch: `-amd64` / `-arm64` variants exist) | PRD-01 (CI smoke) / PRD-03 (worker) | must equal the npm version exactly | [MCR tag list](https://mcr.microsoft.com/v2/playwright/tags/list) |
| PostgreSQL | **18.7** — image `postgres:18.7-bookworm` | PRD-01 | newest *mature* major. 19.0 was released today (2026-11-19); a `.0` on day one is not pinned for a project that must stay reproducible. Revisit at PRD-13. | [postgresql.org versions.json](https://www.postgresql.org/versions.json) · [Docker Hub postgres tags](https://hub.docker.com/_/postgres/tags?name=18.7) |
| RabbitMQ | **4.4.1** (2026-11-16) — image `rabbitmq:4.4.1-management` | PRD-04 | latest stable (4.3.8 is the previous series). Re-verify when PRD-04 starts. | [GitHub releases](https://github.com/rabbitmq/rabbitmq-server/releases) · [Docker Hub rabbitmq tags](https://hub.docker.com/_/rabbitmq/tags?name=4.4.1) |

Not pinned yet (pinned in their own PRD, after checking the official source that day): Testcontainers (PRD-02), OpenTelemetry Collector, Prometheus, Grafana, Jaeger (PRD-12), k6 (PRD-12), BrowserStack SDK (PRD-09 T1).

### 1.1 PRD-01 tooling and libraries (checked 2026-11-19)

Exact versions in `package.json` files (no `^`), resolved in the single `pnpm-lock.yaml`. Source for npm packages: `https://registry.npmjs.org/<name>` (`dist-tags.latest`, release date, `peerDependencies`). Source for Maven artifacts: `https://repo.maven.apache.org/maven2/<group>/<artifact>/maven-metadata.xml`.

| Component | Pin | Note |
|---|---|---|
| TypeScript | **6.0.3** | `latest` is 7.0.2 (native compiler), but `typescript-eslint` 8.73.0 declares `typescript >=4.8.4 <6.1.0`. 6.0.3 is the newest version inside that range. Revisit when typescript-eslint supports 7. |
| ESLint / `@eslint/js` | 10.15.0 / 10.0.1 | flat config, `defineConfig` |
| typescript-eslint | 8.73.0 | `recommendedTypeChecked` with `projectService` |
| eslint-config-prettier / eslint-plugin-react-hooks / globals | 10.1.8 / 7.1.1 / 17.14.0 | |
| Prettier | 3.10.1 | 4.x is alpha only |
| `@types/node` | 26.6.6 | matches Node 26 |
| Express | 5.3.0 | demo-shop API |
| zod | 4.6.7 | demo-shop request validation |
| React / react-dom | 19.3.1 | demo-shop SPA and web placeholder |
| `@types/react` / `@types/react-dom` | 19.3.0 / 19.3.0 | |
| react-router | 8.5.2 (2026-11-18) | demo-shop routing; `engines.node >=22.22` |
| Vite / `@vitejs/plugin-react` | 8.3.8 / 6.3.0 | |
| Spotless Maven plugin | 3.11.0 | `spotless:check` bound to `verify` |
| google-java-format | 2.0.0 | via Spotless |
| Spring Boot starters | `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test` (4.1.2 BOM) | project generated with start.spring.io for Boot 4.1.2 / Java 21 |
| Maven | **3.9.16** (pin kept) | Spring Initializr now generates 3.10.1 (GA on Maven Central, 2026-11-11). This repo keeps the 3.9.16 pin from §1. Moving to 3.10.x needs a separate, deliberate update. |
| Web image | `nginxinc/nginx-unprivileged:1.30.6-alpine3.24` | newest *stable*-line tag on Docker Hub (nginx.org stable is 1.30.7; the image lags one patch). Runs as non-root on port 8080. [Docker Hub tags](https://hub.docker.com/r/nginxinc/nginx-unprivileged/tags) |
| Corepack in images | 0.36.0 (`npm install -g corepack@0.36.0`) | Node 26 images no longer bundle Corepack (checked: `node:26.13.1-bookworm-slim` has no `corepack`) |

**GitHub Actions** (latest release from `gh api repos/<repo>/releases/latest`, tag resolved to its commit SHA):

| Action | Release | Commit SHA |
|---|---|---|
| `actions/checkout` | v7.0.1 (2026-07-20) | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| `actions/setup-node` | v7.1.0 (2026-10-08) | `949feb2413d6458794dcd2491c4babbbce0c15c1` |
| `actions/setup-java` | v6.3.0 (2026-11-14) | `d1374f8ac74474200d16ef8ecee22c7ae7a51880` |
| `actions/upload-artifact` | v7.0.2 (2026-10-07) | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |

**Rules**
- Images are pinned by full version tag; adding `@sha256:` digests is done in PRD-12 (supply-chain hardening).
- `.nvmrc`, `.tool-versions`, `package.json#packageManager` and `.mvn/wrapper/maven-wrapper.properties` must match this table (PRD-01 FR-3).
- The Playwright npm version and Docker image tag must be identical, or browser builds will not match.

## 2. Development machine (measured 2026-11-19)

| Item | Value | How measured |
|---|---|---|
| Hardware | Apple M4, 10 cores (4 performance + 6 efficiency), arm64 | `sysctl -n machdep.cpu.brand_string hw.ncpu hw.perflevel0.physicalcpu hw.perflevel1.physicalcpu` |
| RAM | 16 GiB (17,179,869,184 bytes) | `sysctl -n hw.memsize` |
| Disk free | 94 GiB of 228 GiB | `df -h /` |
| OS | macOS 27.2.1 (build 26B131) | `sw_vers` |

### Toolchain found on this machine vs the pins

| Tool | Found | Pin | Action before PRD-01 |
|---|---|---|---|
| Java default on `PATH` | OpenJDK 26.0.1 (Homebrew) | 21 | **Use JDK 21 for this repo.** Homebrew `openjdk@21` 21.0.11 is installed but not on `PATH`; upgrade it (or install Temurin 21.0.13) and select it per-repo. |
| Maven | not installed | wrapper | none — PRD-01 commits the Maven wrapper |
| Node | v26.0.0 | 26.13.1 | upgrade to 26.13.1 (26.0.0 predates the LTS line) |
| pnpm | 10.34.5 | 12.21.0 | use Corepack with `packageManager: "pnpm@12.21.0"` in PRD-01 |
| Docker CLI | 29.5.2 (context `orbstack`) | — | **No engine running:** `/Applications/OrbStack.app` is absent, so the daemon socket and the `docker compose` plugin symlinks are broken. Install/start a Docker engine before PRD-01 T5. |
| GNU Make | 3.81 | — | OK for the PRD-01 Makefile (avoid GNU Make 4-only features) |
| Python | 3.14.5 | 3.9+ | OK for `tools/progress.py` |
| git / gh | 2.54.0 / 2.96.0 (authenticated) | — | OK |

### Toolchain fixes applied 2026-11-19 (after PRD-00 sign-off)

| Tool | Before | After | Command |
|---|---|---|---|
| Node | 26.0.0 | **26.13.1** ✓ matches pin | `brew upgrade node` |
| JDK 21 | openjdk@21 21.0.11 | **21.0.13.1** ✓ (Homebrew build of the 21.0.13.1 respin) | `brew upgrade openjdk@21` |
| Docker engine | none | OrbStack 2.2.5 running ✓. Docker Engine 29.8.2 in an aarch64 VM with 10 CPUs and 7.8 GiB memory (within the ≤ 8 GiB budget); `docker run --rm hello-world` succeeds | `brew install --cask orbstack`, then first launch by the developer |
| `docker compose` | broken symlink | Docker Compose v5.5.0 ✓ | fixed by the OrbStack install |

| Corepack / pnpm | `pnpm` 10.34.5 and Corepack 0.34.1 came from an old nvm Node 20 install; four dangling `pnpm`/`pnpx`/`yarn`/`yarnpkg` links in `/opt/homebrew/bin` pointed to a removed Node 24 | Corepack **0.36.0** under Node 26 (`/opt/homebrew/bin/corepack`), `pnpm` shim → **12.21.0** ✓ | removed the dangling links, `npm install -g corepack@0.36.0`, `corepack enable pnpm` |

Side effect: Homebrew also upgraded a dependent formula, `opencode` 1.15.10 → 2.3.2.

Corepack 0.34.1 cannot launch pnpm 12, because pnpm 12 ships a native binary instead of `pnpm.cjs`. It also leaves a broken entry in `~/.cache/node/corepack/v1/pnpm/12.21.0`, so delete that folder if you see `Cannot find module …/pnpm.cjs`.

**Using JDK 21 in this repo** (the global default `java` stays 26). Run this before Maven commands; PRD-01's Makefile will set it automatically:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

## 3. Resource budget

Planning limits, enforced as `mem_limit` / `cpus` in Compose from PRD-01 and hardened in PRD-12. Measured usage replaces these numbers once it exists.

| Budget item | Limit | Reasoning |
|---|---|---|
| Docker engine VM total | **≤ 8 GiB RAM, ≤ 6 CPUs** | leaves ~8 GiB for macOS, IDE, browser and the dashboard |
| `postgres` | 512 MiB, 1 CPU | small dataset; tuned shared_buffers later if needed |
| `api` (JVM) | 768 MiB, 1.5 CPU (`-XX:MaxRAMPercentage=75`) | Spring Boot + Flyway + Hikari pool |
| `web` | 256 MiB, 0.5 CPU | static Vite build behind a small server |
| `demo-shop` | 256 MiB, 0.5 CPU | Express API + static SPA |
| `rabbitmq` (PRD-04+) | 512 MiB, 1 CPU | quorum queues, low message volume |
| each `worker` | 1.5 GiB, 1.5 CPU, `pids_limit: 512` | one browser context at a time per slot; Chromium alone needs ~300–500 MiB under load (to be measured) |
| observability profile (PRD-12) | 1.5 GiB total | collector, Prometheus, Grafana, Jaeger; run only on demand |
| **Default worker count** | **2** (hard maximum 3) | PRD-00 requires ≤ 3; 2 × 1.5 GiB fits the VM budget with the core services |
| **Browser concurrency cap** (`global_max_sessions`) | **3** | matches PRD-04 Q-001 (cap 3); with 2 workers this means one worker may run 2 slots |
| Worker slots | 1 by default, 2 allowed only when `global_max_sessions` permits | keeps per-container memory predictable |

Budget check (default profile, PRD-03 shape): 512 + 768 + 256 + 256 + 2 × 1,536 = 4,864 MiB ≤ 8 GiB. With `queue` (+512 MiB) = 5,376 MiB. With `obs` (+1,536 MiB) = 6,912 MiB — still under budget, but `obs` and benchmark runs should not overlap (benchmarks must record the profile used).

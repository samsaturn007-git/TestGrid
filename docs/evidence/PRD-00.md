# Evidence — PRD-00

**PRD:** `prds/PRD-00-governance.md`  
**Branch / commits:** `prd-00/governance` — `2872bf0` (kit import on `main`), `8d1bc20` (ADRs, index, PR template, conventions), `6d00dcd` (conflict register, risks, walkthrough, versions, vocab check)  
**Reviewer:** developer (sign-off pending — see AC-3)  
**Date:** 2026-11-19 (UTC)  
**Outcome:** blocked — every task is implemented; waiting only on the developer's approval of the demo walkthrough and non-goals (AC-3)

## Acceptance criteria with evidence

| Criterion | Pass? | Test ID(s) | Proof (file, test and observable behaviour) |
|---|---|---|---|
| AC-1 | Yes | — (design review) | Each of `docs/decisions/ADR-001…004` lists ≥ 2 rejected options with a concrete reason and has a **"What this does NOT claim"** block. Rejected counts: ADR-001 = 3 (Node-only, Go, MongoDB); ADR-002 = 4 (RabbitMQ day one, Kafka, Redis Streams, DB polling forever); ADR-003 = 3 (uploaded specs, Git-URL runners, free-form URL + regex); ADR-004 = 3 (broker FIFO + consumer priority, RabbitMQ priority queues, pure DB polling). Command output below. |
| AC-2 | Yes | — (grep check) | `docs/CONFLICT_REGISTER.md`: 14 inherited conflicts re-checked and closed, 6 new observations ruled non-contradictory; **0 open**. `bash tools/vocab_check.sh` exit 0 (output below). |
| AC-3 | **Pending** | — | `docs/DEMO_WALKTHROUGH.md` (marked *TARGET, NOT YET RUNNABLE*) and the non-goals (MASTER_PRD §5, repeated at the end of the walkthrough) await the developer's explicit approval. Record it in the sign-off section below. |
| AC-4 | Yes | — (link review) | Every row of `docs/VERSIONS.md` §1 has an official source link and the check date 2026-11-19. Each value came from the live source today (commands below), not from memory. |

## Tasks completed

| Task | Commit(s) | Notes |
|---|---|---|
| T1 | `6d00dcd` | Read MASTER, AGENTS, ARCHITECTURE, CONTRACTS, SECURITY, TEST MATRIX, CHANGES_v2 and all PRDs; conflict register + `tools/vocab_check.sh`. |
| T2 | `8d1bc20` | ADR-001 to ADR-004, status *Proposed* until sign-off; index in `docs/decisions/README.md`. |
| T3 | `6d00dcd` | `docs/RISKS.md`: 8 risks with likelihood, impact, mitigation, verifying PRD and test IDs. |
| T4 | `6d00dcd` | `docs/DEMO_WALKTHROUGH.md`: 10-minute clean-clone path with pass, product defect, worker-kill recovery and CI gate, each with expected result, owning PRD and test ID. |
| T5 | `6d00dcd` | `docs/VERSIONS.md`: pins, sources, measured machine specs, toolchain gaps, resource budget (worker count 2 ≤ 3, cap 3). |
| T6 | `2872bf0`, `8d1bc20` | `git init -b main`, `.gitignore` (Java, Node, Playwright, env), `.github/pull_request_template.md`, `docs/decisions/README.md`, `CONTRIBUTING.md` (branch + commit conventions), branch `prd-00/governance`. |

## Changed files

- `.gitignore` — Java/Maven, Node/pnpm, Playwright, env/secrets, local data.
- `.github/pull_request_template.md` — evidence and threat checklist per PR.
- `CONTRIBUTING.md` — one-PRD rule, `prd-XX/<topic>` branches, `type(prd-XX): …` commits, close-out steps.
- `docs/decisions/README.md`, `ADR-001-stack.md`, `ADR-002-phased-delivery.md`, `ADR-003-safe-execution-boundary.md`, `ADR-004-dispatch-ownership.md` — FR-2.
- `docs/CONFLICT_REGISTER.md` — FR-3.
- `docs/RISKS.md` — FR-4.
- `docs/DEMO_WALKTHROUGH.md` — FR-5.
- `docs/VERSIONS.md` — FR-6.
- `tools/vocab_check.sh` — repeatable AC-2 check.
- `progress.json`, `PROGRESS.md` — PRD-00 `in_progress`, T1–T6 checked via `tools/progress.py`.

## Actual executed commands and outputs

```text
$ shasum -a 256 -c MANIFEST.sha256 | grep -v ': OK'
(no output — all 36 entries OK)

$ python3 tools/progress.py lint
lint OK: tracker matches PRD files            (exit 0)

$ python3 tools/progress.py start PRD-00
PRD-00: start OK

$ bash tools/vocab_check.sh
Canonical enum values (contracts §2.1): 52
Non-canonical spellings: none
vocab_check OK                                (exit 0)

$ for v in QUEUED CLAIMED RUNNING PASSED FAILED TIMED_OUT INFRA_ERROR CANCELLED ABANDONED; do …count in MASTER, docs, PRDs…; done
QUEUED=14 CLAIMED=14 RUNNING=14 PASSED=17 FAILED=13 TIMED_OUT=15 INFRA_ERROR=28 CANCELLED=15 ABANDONED=13

$ grep -oHE '\b(CANCELED|TIMEDOUT|TIME_OUT|SUCCEEDED|SUCCESS|FAILURE|ERRORED|INFRA_FAILURE|IN_PROGRESS|PENDING|COMPLETED|ABANDON|LEASED|PASSING|FAILING)\b' MASTER_PRD.md docs/*.md prds/*.md | sort | uniq -c
   1 docs/API_AND_DATA_CONTRACTS.md:FAILING     (case_health label — allowed, see CONFLICT_REGISTER N3)
   1 prds/PRD-07-failure-intelligence.md:FAILING

$ grep -c "Rejected because" docs/decisions/ADR-00*.md
docs/decisions/ADR-001-stack.md:3
docs/decisions/ADR-002-phased-delivery.md:4
docs/decisions/ADR-003-safe-execution-boundary.md:3
docs/decisions/ADR-004-dispatch-ownership.md:3
$ grep -c "What this does NOT claim" docs/decisions/ADR-00*.md
(1 in each of the four files)

# T5 version sources (all fetched 2026-11-19)
$ curl -fsS https://nodejs.org/dist/index.json | …first entry per LTS line…
v26.13.1 2026-11-10 Lithium | v24.22.3 2026-11-10 Krypton | v22.23.5 2026-11-11 Jod
$ curl -fsS https://raw.githubusercontent.com/nodejs/Release/main/schedule.json
v24 … 'maintenance': '2026-10-20' …   v26 … 'lts': '2026-10-28', 'maintenance': '2027-10-20', 'end': '2029-04-30'
$ curl -fsS "https://api.github.com/repos/spring-projects/spring-boot/releases?per_page=30"
v4.2.0-RC2 2026-11-05 True | v4.2.0-RC1 2026-10-22 True | v4.1.2 2026-10-22 False | v4.0.9 2026-10-22 False
$ curl -fsSL https://docs.spring.io/spring-boot/system-requirements.html
Spring Boot 4.1.2 requires at least Java 17 and is compatible with versions up to and including Java 27 … Maven 3.6.3 or later
$ curl -fsS https://registry.npmjs.org/pnpm   → dist-tags latest: 12.21.0, next-12: 12.22.0
$ curl -fsS https://registry.npmjs.org/@playwright/test → latest: 1.65.1 (2026-11-13)
$ curl -fsS https://mcr.microsoft.com/v2/playwright/tags/list → includes v1.65.1, v1.65.1-noble, v1.65.1-noble-arm64
$ curl -fsS https://www.postgresql.org/versions.json → 18: latestMinor 7 (supported, EOL 2030-11-14); 19: latestMinor 0, firstRelDate 2026-11-19
$ curl -fsS "https://api.github.com/repos/rabbitmq/rabbitmq-server/releases?per_page=20" → v4.4.1 2026-11-16 False | v4.3.8 2026-11-16 False
$ curl -fsS "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=aarch64&image_type=jdk&os=mac" → jdk-21.0.13.1+2 2026-11-18
$ curl -fsS https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml → latest 3.9.x: 3.9.16 (release tag 4.0.0-rc-7)
$ curl -fsS https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/maven-metadata.xml → <release>3.3.4</release>
Docker Hub tags confirmed: postgres:18.7-bookworm, rabbitmq:4.4.1-management, node:26.13.1-bookworm-slim, eclipse-temurin:21.0.13_9-jre-noble

# Machine measurements
$ sysctl -n hw.memsize hw.ncpu machdep.cpu.brand_string → 17179869184 / 10 / Apple M4
$ sysctl -n hw.perflevel0.physicalcpu hw.perflevel1.physicalcpu → 4 / 6
$ sw_vers → macOS 27.2.1 (26B131);  uname -m → arm64
$ docker info → Client 29.5.2, context orbstack; Server: failed to connect … /Users/…/.orbstack/run/docker.sock: no such file or directory
$ java -version → openjdk 26.0.1;  /opt/homebrew/opt/openjdk@21/bin/java -version → 21.0.11
```

## Negative / failure tests

- **Case:** a deliberately wrong problem-slug match.
- **How it was injected:** the first version of `vocab_check.sh` matched `ADR-003 manifest-only` (in `docs/RISKS.md`) as an HTTP status + slug.
- **Observed state and recovery:** the script exited 1 with `Problem slug not in contracts §6: manifest-only`, proving the check fails loudly. The regex was narrowed to real HTTP codes (2xx–5xx not preceded by `-`); re-run exit 0 and it now checks `idempotency-conflict` and `lease-expired`.

## Security and threat check

- **Input restrictions checked:** ADR-003 makes URLs, code, regexes and flags unrepresentable in requests; contracts §1 and SECURITY rule 1 agree (CONFLICT_REGISTER §1).
- **Secret or artifact leak scan (canary):** not applicable yet (no code or artifacts). `.gitignore` excludes `.env*` except `.env.example`, keys and certificates. `git ls-files` contains no secrets.
- **New attack surface introduced, and its mitigation:** none; this PRD adds documentation and a read-only shell script.

## Skipped or mocked tests

- No application tests exist in PRD-00 (out of scope: no code, Compose or CI).
- Link review was done by fetching each source with `curl`/`WebFetch` today. Docker Hub tag pages in `VERSIONS.md` link to the human UI; the tags were confirmed through the Docker Hub API.

## Screenshots / demo / raw data links

- None for PRD-00 ("Demo moment: none"). All artifacts are the repository files listed above.

## Known limitations and deferred improvements

- **Environment blockers for PRD-01 (tracked in `docs/VERSIONS.md` §2):** no Docker engine is running (OrbStack app missing), JDK 21 is not the default `java`, Node 26.0.0 needs upgrading to 26.13.1, and pnpm 10 → 12.21.0 through Corepack.
- Image digests (`@sha256:`) are deferred to PRD-12.
- RabbitMQ and the observability stack versions must be re-verified when PRD-04 and PRD-12 begin.
- PostgreSQL 19.0 shipped today; 18.7 is pinned deliberately and should be reconsidered at PRD-13.
- No GitHub remote has been created yet; the repository is local.

## Developer sign-off (AC-3)

- [ ] I approve `docs/DEMO_WALKTHROUGH.md` as the target acceptance path.
- [ ] I approve the non-goals in MASTER_PRD §5.
- [ ] I have read ADR-001 to ADR-004 and accept them (their status changes to *Accepted*).

Approved by: — (pending) on —

## Explain-back review (developer's own words)

Answer these yourself, without AI, before closing PRD-00:

1. **The three most important engineering decisions in this PRD, and why:** (a) why the database dispatcher, not RabbitMQ, decides execution order and enforces the session cap (ADR-004); (b) why the API never accepts a URL or a test script, even an allowlisted one (ADR-003); (c) why the first worker polls the database before a broker is introduced (ADR-002).
2. **What happens if the main operation fails halfway through?** A worker claims a target, starts Chromium, then the container is killed. Walk through which rows exist, what the reaper does, and why a late `complete` call from that worker cannot overwrite the retry's result.
3. **What would you change for production?** Name one thing you would change about the dispatcher (ADR-004 consequences) and one about the execution boundary if real users wanted to run their own tests.

# Evidence — PRD-08

**PRD:** `prds/PRD-08-ci-quality-gates.md`  
**Branch / commits:** `prd-08/quality-gate`, opened as https://github.com/samsaturn007-git/TestGrid/pull/11. Kickoff rulings `f6386fc`; tracker start `6e8ed2a`; T1 `00985fc`, docs `c51599b`; T2 `542fb61`, docs `22a4143`; T3 `f9b3889`, docs `9a5895a`; T4 `bef8d47`, docs `6f08592`; T5 `975879e`, `8d8b60e`, docs `f2caf3d`; T6 `213708d`, docs `f51e3a0`; T7 `783fc26`, `be4873c`, `57e3ab2`, docs `01c144a`; tracker `789327a`, `9c52295`, `057126c`, `8b3b706`, `3d0baf8`, `7899cdd` (T1–T6 checked). Red-gate demo: https://github.com/samsaturn007-git/TestGrid/pull/12 (throwaway, into `prd-08/quality-gate`, closed after the run).  
**Reviewer:** the developer (repository owner), pending: the explain-back below is theirs to answer.  
**Date:** 2026-11-25 (UTC)  
**Outcome:** pass. Every AC was checked in tests, against the live Compose stack and on GitHub-hosted runners (below). `tools/progress.py done` stays refused until the developer has answered the explain-back.

Reference machine: Apple M4, 16 GiB, macOS 27.2, OrbStack, JDK 21, Node 26.13.1, pnpm 12.21.0. **No new dependency**: the CLI and the scripts use Node itself (`node:util` `parseArgs`, global `fetch`); the API uses what it already had (no Spring Security: two servlet filters). The only GitHub action added to a workflow is `actions/upload-artifact`, already pinned in `ci.yml` and re-checked (VERSIONS §1.1). API tests are **live** against Testcontainers PostgreSQL; CLI and web unit tests use fakes (see "Skipped or mocked tests"); scenario runs are **live** against Compose locally and on GitHub runners. Locally, the CI token was a fresh `openssl rand -hex 24` value per session, passed through the shell and never written to the repository or `.env`.

## Acceptance criteria with evidence

| Criterion | Pass? | Test ID(s) | Proof (file, test and observable behaviour) |
|---|---|---|---|
| AC-1 | Yes | CI-001 | Unit: `QualityRulesTest.ci001AGreenRunPassesEveryRule`; API: `QualityApiTest.ci001AGreenRunPassesOnceWhateverHowOftenItIsRead` (one evaluation, one `QUALITY_EVALUATED` event however often it is read); CLI: `main.test.ts` "ci001 exits 0 on PASS…". **Live (local):** `make gate-scenarios` "pass (CI-001) expected 0 got 0, outcome PASS" (`raw/prd-08-gate-scenarios-local/`). **GitHub:** PR #11, `testgrid-gate` [run 45450008169](https://github.com/samsaturn007-git/TestGrid/actions/runs/45450008169): the CLI created run `e29c502a…` on chromium and firefox, polled it with backoff (1 s, 2.2 s, 4.6 s, 8.8 s, 9.6 s …), got `"outcome":"PASS","exitCode":0` under policy v1, the check went green and `deploy demo (simulated)` ran after it. Every later push to PR #11 repeats this. |
| AC-2 | Yes | CI-002 | Unit: `QualityRulesTest.ci002ACriticalCaseFailureFailsAndTheEvidenceNamesCaseAndEngine`; API: `QualityApiTest.ci002ACriticalFailureFailsAndNamesTheCaseAndEngine`; CLI: `main.test.ts` "ci002 exits 1 on FAIL and names the case and engine", `markdown.test.ts` "ci002 writes the rule table and names the failed case and engine". **Live (local):** "fail (CI-002) expected 1 got 1, failed: cart-total@chromium critical"; the T5 dry run of the gate step on chromium and firefox wrote a summary listing `cart-total` FAILED on both, critical. **GitHub:** red-gate demo PR #12 (`GATE_FIXTURE: PRODUCT_DEFECT`), [run 45467626156](https://github.com/samsaturn007-git/TestGrid/actions/runs/45467626156): the CLI printed `"outcome":"FAIL","exitCode":1,"reason":"blocking rules failed: critical-cases, max-failed-cases"` with `failedCases` `cart-total` on `local:chromium:0` and `local:firefox:0`, both critical; the step ended "Process completed with exit code 1", so the `quality gate` check is red; `gate-artifacts.ts` saved 956 KiB with 0 missing and the `testgrid-gate` artifact (399,991 bytes) was uploaded; **`deploy demo (simulated)` was skipped**, the deploy veto. |
| AC-3 | Yes | CI-003, CI-004 | Unit: `QualityRulesTest.Indeterminate` (7 cases: missing target, INFRA_ERROR never PASS even with the infra rule off, a target-level timeout without results, a cancelled run, every shard must be conclusive, FAIL wins over INDETERMINATE) and `passNeverCoexistsWithAMissingOrInconclusiveRequiredTarget` (5,000 generated runs, invariant I9). CLI: "ci003 exits 2, never 0, when the run outlives the deadline", "ci003 exits 2 when the API stays unreachable until the deadline", "a server that never answers ends in exit 2 at the deadline" (real HTTP), and a 401, 403, 400, 409 and 422 each exit 3. API: `ApiTokenApiTest.ci004ABadTokenIs401EverywhereAndNeverFallsBackToAnonymous` (7 bad headers × 3 endpoints). **Live (local):** "workers stopped (CI-003) expected 2 got 2, deadlineReached true, run QUEUED"; "bad token (CI-004) expected 3 got 3, 401 unauthorized"; "no token on a CI run (CI-004) expected 3 got 3, 401 unauthorized"; in T3 a 3 s deadline gave exit 2 with `deadlineReached: true`. **GitHub:** `scenarios` job of [run 45467948830](https://github.com/samsaturn007-git/TestGrid/actions/runs/45467948830) (head `9163747`, success): "workers stopped (CI-003) expected 2 got 2, deadlineReached true, run QUEUED"; "bad token (CI-004) expected 3 got 3, 401 unauthorized"; "no token on a CI run (CI-004) expected 3 got 3, 401 unauthorized". |
| AC-4 | Yes | QUA-001, QUA-002 | Unit: `QualityRulesTest.Quarantine` (5 cases). API: `QuarantineApiTest.qua001AndQua002QuarantineFlipsFailToPassUntilItExpires` (FAIL → quarantine → PASS with `QUARANTINED_FAIL` → expiry → FAIL; `QUARANTINE_EXPIRED` written once, by `system`), `theCapHoldsUnderConcurrentRequests`, `autoModeQuarantinesAFlakyCaseOnlyFromTenResultsAndOnlyForLaterRuns`. Web: `QualityPanel.test.tsx` "qua001 reports a quarantined failure without counting it". **Live (local):** "quarantined (QUA-001) expected 0 got 0, quarantinedFailures: cart-total@chromium QUARANTINED_FAIL"; "quarantine expired (QUA-002) expected 1 got 1"; "both changes audited (AC-4): QUARANTINE_CREATED by local, QUARANTINE_EXPIRED by system". **GitHub:** the same `scenarios` job: "quarantined (QUA-001) expected 0 got 0, quarantinedFailures: cart-total@chromium QUARANTINED_FAIL"; "quarantine expired (QUA-002) expected 1 got 1, outcome FAIL"; "both changes audited (AC-4) expected 2 got 2, QUARANTINE_CREATED by local, QUARANTINE_EXPIRED by system". |
| AC-5 | Yes | (FR-6) | `make workflow-audit` (in `ci.yml`): both workflows have only `pull_request` (+ `workflow_dispatch` with a choice input), top-level `contents: read` with no job widening it, every `uses:` pinned to a 40-hex SHA, no `secrets.*`, `persist-credentials: false`, no `github.event`/`inputs` expression inside a `run:` script; fed a deliberately bad workflow it reports all eight kinds of violation. Fork PRs: the workflow uses no repository secret at all (the CI token is random per job), so a fork has nothing to read; GitHub gives `pull_request` runs from forks a read-only token. Manifest only: "suite outside the manifest expected 3 got 3, 422 unknown-suite", and the CLI accepts only enum engines and fixture modes. Secret scan: `gitleaks git` 8.30.1 over all 211 commits, "no leaks found", with six reviewed false positives (plus one in the ignore file's own first comment) listed by fingerprint in `.gitleaksignore`. Live: the CI token appears 0 times in the API's logs, the committed diff and the saved scenario reports. |

## Tasks completed

| Task | Commit(s) | Notes |
|---|---|---|
| T1 | `00985fc`, `c51599b` | V14 `quality_policies` (immutable versions, one active) and append-only `quality_evaluations`; `GET/PUT /projects/{id}/quality-policy` with an audited reason; `QualityRules` (7 rules, pure); evaluation after the finishing commit through a loop, a sweep and `GET /runs/{id}/quality` (N35); rulings N34, N35, N37. |
| T2 | `542fb61`, `22a4143` | V15 `quarantines` (≤ 30 days by CHECK, state from the database clock); create / revoke with a per-project advisory lock, the `maxActive` cap and `409 quarantine-conflict`; expiry recorded by the sweep; AUTO (FLAKY, n ≥ 10, 7 days, later runs only) and SUGGEST; ruling N38. |
| T3 | `f9b3889`, `9a5895a` | `cli/`: `run`, `status`, `gate`, `compare`; exit codes 0/1/2/3; backoff 1 → 10 s ± 20 % to `--timeout`; retries for network errors, 429 and 5xx only; idempotency key from the CI job; token from `TESTGRID_TOKEN` only. |
| T4 | `bef8d47`, `6f08592` | V16 `api_tokens` (hash only); the `ci` token seeded from `TESTGRID_CI_TOKEN` (rotated, revoked, audited); `ApiTokenFilter` (401 / 403, never anonymous fallback); `trigger: CI` needs `runs:write`; ruling N33. |
| T5 | `975879e`, `8d8b60e`, `f2caf3d` | `testgrid-gate.yml` (in-runner stack, CLI gate, `--summary-file` to `$GITHUB_STEP_SUMMARY`, sha256-checked failure artifacts, `deploy-demo` needs the gate); `workflow-audit.sh` in `ci.yml`. |
| T6 | `213708d`, `f51e3a0` | Quality panel on the run page, `/projects/:id/policy`, `/quarantines`; the a11y e2e covers both new pages in both themes. |
| T7 | `783fc26`, `be4873c`, `57e3ab2`, `01c144a`, this file | `scripts/gate-scenarios.sh` (`make gate-scenarios`) and the workflow's `scenarios` job; gitleaks clean; CI security in `SECURITY_AND_TEST_STRATEGY.md`, the gate runbook in `RUNBOOK.md` §8. |

## Changed files

- `api/src/main/resources/db/migration/V14__quality.sql`, `V15__quarantines.sql`, `V16__api_tokens.sql`: the three tables, their guards and indexes, version 1 of the policy.
- `api/src/main/java/dev/testgrid/api/quality/*`: policy model, repository, service and controller; `QualityRules`; facts; evaluator, signal and loop; the run-quality endpoint.
- `api/src/main/java/dev/testgrid/api/quarantine/*`: quarantine model, repository, service and controller.
- `api/src/main/java/dev/testgrid/api/auth/*`: scopes, principal, token repository, the CI token seeder and `ApiTokenFilter`.
- `api/…/internal/AttemptRepository.java` (hand a finished run to the quality loop after commit), `compare/RunComparisonService.java` (comparison against last green with the policy's thresholds), `run/RunController.java` (CI runs need `runs:write`), `problem/ProblemType.java` (`quarantine-conflict`), `audit/AuditRepository.java` (`system` actor), `domain/QualityOutcome.java`, `application.properties`.
- `api/src/test/…`: `QualityRulesTest`, `QualityApiTest`, `QualityLoopTest`, `QuarantineApiTest`, `ApiTokenApiTest`, `CiTokenSeederTest`; test bases reset policies and quarantines; three idempotency tests send `MANUAL` instead of an unauthenticated `CI`.
- `api/openapi.yaml`, `packages/contracts/src/api.ts`: regenerated.
- `cli/*`: the CLI, its tests and README; root `package.json` script `testgrid`.
- `web/src/quality/*`, `web/src/api/*`, `web/src/app/*`, `web/src/runs/detail/RunDetailPage.tsx`, `web/src/test/qualityFixtures.ts`, `web/e2e/a11y.spec.ts`: the dashboard pages and their tests.
- `.github/workflows/testgrid-gate.yml`, `.github/workflows/ci.yml`: the gate workflow; the audit step.
- `scripts/gate-artifacts.ts`, `scripts/workflow-audit.sh`, `scripts/gate-scenarios.sh`, `Makefile` (`workflow-audit`, `gate-scenarios`), `.gitignore`, `.gitleaksignore`, `.env.example`, `infra/compose/compose.yaml` (`TESTGRID_CI_TOKEN`).
- `docs/API_AND_DATA_CONTRACTS.md` (§2.4, §3, §4, §5, §6, §11, §12), `docs/CONFLICT_REGISTER.md` (N33–N38), `docs/SECURITY_AND_TEST_STRATEGY.md`, `docs/RUNBOOK.md` §8, `docs/VERSIONS.md` §1.1, this file, `docs/evidence/raw/prd-08-gate-scenarios-local/`.

## Actual executed commands and outputs

```text
$ make check && make test            (after T4; T5–T7 changed no Java)
exit=0
java tests=602 failures=0 errors=0   (surefire XML; 536 before PRD-08)
cli test: ℹ pass 40  ℹ fail 0        (after T5)
demo-shop test: ℹ pass 42  ℹ fail 0
worker test: ℹ pass 111  ℹ fail 0
web test: Tests 290 passed (290)     (after T6; 272 before PRD-08)

$ make contracts-check
contracts-check OK: api/openapi.yaml and packages/contracts/src/api.ts match the code

$ bash tools/vocab_check.sh && make workflow-audit
vocab_check OK
workflow-audit OK

$ pnpm --filter web exec playwright test e2e/a11y.spec.ts      (live Compose, rebuilt web image)
18 passed (23.7s)                                             (incl. the policy and quarantine pages, both themes)
$ pnpm --filter web exec playwright test e2e/dashboard.spec.ts
4 passed (1.8m)

$ TESTGRID_TOKEN=<this session's token> make gate-scenarios      (live Compose)
pass (CI-001)                expected 0  got 0  ok         outcome PASS
fail (CI-002)                expected 1  got 1  ok         failed: cart-total@chromium critical
bad token (CI-004)           expected 3  got 3  ok         401 unauthorized
no token on a CI run (CI-004) expected 3  got 3  ok         401 unauthorized
suite outside the manifest   expected 3  got 3  ok         422 unknown-suite
workers stopped (CI-003)     expected 2  got 2  ok         deadlineReached true, run QUEUED
quarantined (QUA-001)        expected 0  got 0  ok         quarantinedFailures: cart-total@chromium QUARANTINED_FAIL
quarantine expired (QUA-002) expected 1  got 1  ok         outcome FAIL
both changes audited (AC-4)  expected 2  got 2  ok         QUARANTINE_CREATED by local, QUARANTINE_EXPIRED by system
gate-scenarios OK
exit=0

$ gitleaks git --redact --no-banner .
INF 211 commits scanned.
INF no leaks found

$ docker logs testgrid-api-1 2>&1 | grep -c "<this session's CI token>"
0
```

## Negative / failure tests

- **Workers stopped:** `docker compose stop worker`, then `testgrid run … --timeout 45s`: the run stays `QUEUED`, the CLI reports `deadlineReached: true`, exit 2 (never 0); the stuck run is cancelled afterwards so it doesn't start later.
- **API unreachable / hanging:** fake fetch that always fails, and a real HTTP server that never answers: both end at the deadline with exit 2.
- **Bad, revoked, expired, malformed token; no token on a CI run:** 401, exit 3; a token lacking the scope: 403, exit 3. The CI token can't `PUT` the policy or create quarantines (403).
- **A crash between the finishing commit and the evaluation:** `QualityLoopTest.theSweepEvaluatesAFinishedRunThatWasNeverSignalled` finishes a run behind the API's back; the sweep evaluates it.
- **Concurrent quarantines past the cap:** 6 parallel POSTs with `maxActive: 2` give exactly two `201` and four `409`.
- **Tampering in the database:** triggers refuse editing or deleting a policy version, a quarantine or an evaluation.
- **Maven Central rate limit (unplanned):** the PR #11 smoke job once failed building the API image (`429 Too Many Requests` from Maven Central, twice). The branch hadn't touched `pom.xml` or the Dockerfile and the gate job built the same image in the same push; a rerun of the failed job passed.

## Security and threat check

- Input restrictions checked: policy fields are typed with ranges and a target allowlist; quarantine case keys must exist in a current suite; expiries ≤ 30 days on the database clock; CLI flags are enums and manifest keys; `--token` refused; `--api-url` must be plain http(s); the summary escapes table and HTML characters.
- Secret or artifact leak scan (canary): `gitleaks git` clean over the whole history; the CI token never in logs, the diff or the reports; `gate-artifacts.ts` sends no token and keeps only sha256-checked bytes from the API's signed links.
- New attack surface and its mitigation: (1) the token filter on `/api/v1` (hash lookup plus `MessageDigest.isEqual`; a presented token is always checked); (2) quarantine and policy writes stay unauthenticated for the dashboard until PRD-12, as overrides were in PRD-07: every change needs a reason and is audited, quarantines expire and are capped, and the CI token can't make them; (3) the gate workflow (see AC-5 and `SECURITY_AND_TEST_STRATEGY.md` "CI gate security").

## Skipped or mocked tests

- CLI unit tests (40) run against a fake fetch and a fake clock; one test uses real HTTP. The CLI was also run live (T3, T4, T7).
- Web unit tests (290) use a stubbed fetch; the a11y and dashboard Playwright specs run live.
- A PR from a real fork was not opened (no second account). The guarantee rests on the workflow using no secrets and on `workflow-audit.sh`, not on an observed fork run.
- `act` is not installed (N36); every workflow claim is from GitHub-hosted runs.

## Screenshots / demo / raw data links

- PR #11: https://github.com/samsaturn007-git/TestGrid/pull/11; first green gate and deploy: https://github.com/samsaturn007-git/TestGrid/actions/runs/45450008169
- PR #11 at head `9163747` (T7 complete), [run 45467948830](https://github.com/samsaturn007-git/TestGrid/actions/runs/45467948830): `quality gate` success and `deploy demo (simulated)` success; its `scenarios` job: success, "gate-scenarios OK" with all eight checks as expected (fail 1, bad token 3, no token 3, suite outside the manifest 3, workers stopped 2, quarantined 0, expired 1, two audit rows), reports uploaded as `testgrid-gate-scenarios`. The demo run 45467626156's `scenarios` job also passed. (Runs 45467533609 and 45467588805 were cancelled by the next push: the workflow's concurrency group.)
- Red-gate demo PR #12: https://github.com/samsaturn007-git/TestGrid/pull/12; its gate run https://github.com/samsaturn007-git/TestGrid/actions/runs/45467626156: `quality gate` failure (exit 1, `cart-total` on chromium and firefox), `deploy demo (simulated)` skipped, `testgrid-gate` artifact uploaded.
- Local scenario reports: `docs/evidence/raw/prd-08-gate-scenarios-local/` (`results.txt` and one CLI JSON report per scenario).

## Known limitations and deferred improvements

- No access control on policy and quarantine writes until PRD-12; they are audited as `local`.
- A quarantine names a case key across suites (contracts §3 has no `suite_key`); case keys don't repeat across suites today (N38).
- `gitleaks` runs by hand and in this evidence; it joins CI in PRD-12 (SEC-003). The working-tree scan (`gitleaks dir`) shows the same reviewed false positives at their current line numbers; the history scan is the one of record.
- The deploy job is simulated; PRD-12 adds the deployable demo.
- Each gate run builds the whole stack in the runner (a few minutes); image caching is a later improvement.
- AUTO mode never fires if `testgrid.case-health.window-size` is below 10.

## Explain-back review (developer's own words)

The PRD is done only when the developer can explain the exit codes, the quarantine safeguards and the fork-PR security without AI. Answer below without notes; `tools/progress.py done` waits for it.

1. The three most important engineering decisions in this PRD, and why:
   [developer: your answer]
2. What happens if the main operation fails halfway through? (For example: the API crashes after a run finishes but before it is evaluated; the CLI loses the API in the middle of `--wait`.)
   [developer: your answer]
3. What would you change for production?
   [developer: your answer]

### Quiz (answer without notes)

1. Why does a deadline or an unreachable API give exit 2 and never 0, and why is a bad token 3 rather than 2?
2. Which four safeguards stop a quarantine from hiding a real bug, and where is each enforced (database, API or UI)?
3. Why is the workflow `pull_request` and not `pull_request_target`, and why does a fork's PR have nothing to steal here?
4. Why is a run evaluated after its finishing transaction commits rather than inside it, and how does the sweep make that safe?
5. A critical case fails on chromium while firefox ends `INFRA_ERROR`. What is the outcome and the exit code, and why?

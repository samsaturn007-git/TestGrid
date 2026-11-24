# Evidence — PRD-07

**PRD:** `prds/PRD-07-failure-intelligence.md`  
**Branch / commits:** `prd-07/failure-intelligence`, not pushed yet. Tracker start `6c7291f`; kickoff rulings N24–N29 `064b1b5`; T1 `27ad0f2`, `d21232f`, docs `ba77e00`; T2 `388b897`, `226c16a`, `04822fb`, `b94c283`, docs `de33095`; T3 `e86f66f`, `bf9a5b3`, `ef9a77f`, `162f35d`, docs `13292c3`; T4 `b3e9917`, `727c0d0`, `3c11439`, docs `829ce8c`; T5 `248fdd5`, `b07a108`, docs `7a43142`; T6 `0df662a`, `a069ef8`, `dccfeb4`, `4da938b`, `03e6914`, docs `b8e43e8`; T7 `3b8d775`, `d772aac` and the evidence commit that adds this file; tracker `a25bedf`, `a7793ee`, `3c50bc4`, `29af5d4`, `d84b32b`, `acd5838` (T1–T6 checked).  
**Reviewer:** the developer (repository owner). Not reviewed yet: `tools/progress.py done PRD-07` has not been run and must wait for that review.  
**Date:** 2026-11-24 (UTC)  
**Outcome:** pass, pending the developer's review. Every AC was checked against Testcontainers PostgreSQL and the live Compose stack (below). GitHub Actions has not run on this branch yet, because nothing has been pushed.

Reference machine: Apple M4, 16 GiB, macOS 27.2, OrbStack, JDK 21, Node 26.13.1, pnpm 12.21.0, PostgreSQL 18.7, RabbitMQ 4.4.1, `@playwright/test` 1.65.1. **No new dependency** (N28, N29): normalisation and hashing use the JDK, the rule table is read with the SnakeYAML and Jackson already on the classpath, and the harness runs on Node's native TypeScript. `docs/VERSIONS.md` is unchanged. API tests are **live** against Testcontainers PostgreSQL. Worker and web unit tests use fakes (see "Skipped or mocked tests"). The evaluation and the AC-3/AC-4 captures are **live** against a separate Compose project (`COMPOSE_PROJECT_NAME=testgrid-eval`) with the real worker, demo shop and Playwright on all three engines. The developer's own `testgrid` volumes were never touched. Only the fake values from `.env.example` were used.

## Acceptance criteria with evidence

| Criterion | Pass? | Test ID(s) | Proof (file, test and observable behaviour) |
|---|---|---|---|
| AC-1 | Yes | INT-001 | Unit: `FingerprintV1Test.int001TheSameSeededFailureHasOneFingerprintAcrossRuns` and `…int001OrderNumbersTimestampsIdsAndPathsDoNotSplitAGroup` (order numbers, ISO and epoch timestamps, UUIDs, hex addresses, temp paths, query strings and the timing-dependent `Call log:` all differ; one fingerprint). `fpV1IsPinnedSoItNeverChangesSilently` pins the hash, cross-checked with Python's `hashlib`. API: `FailureGroupApiTest.int001ARepeatedAssertionIsOneGroupThatBecomesProductSuspected`. **Live** (`data/failure-eval.csv`): the `PRODUCT_DEFECT` failure, repeated in 5 runs with 5 different seeds on 3 engines, is one fingerprint, `31e12582bd8cb543`, for all 15 occurrences (5 runs × chromium, firefox, webkit), in both three-engine runs. The same holds for every other scenario: `SELECTOR_DRIFT` is one group of 30, `TIMEOUT` one group of 30, the flake one group of 12. The live `PRODUCT_DEFECT` message has no order number; the order-number and timestamp variants are covered by the unit test above. |
| AC-2 | Yes | INT-002 | Unit: `FingerprintV1Test.int002DifferentFixtureFailuresGetDifferentFingerprints`, `noCollisionsAmongManyDistinctFailures`, `theErrorClassAndEveryFrameArePartOfTheFingerprint`; `FailureClassifierTest.int002NoInfrastructureFaultIsEverLabelledProductSuspected` (every infrastructure error class against every combination of occurrences, attempts, retries, statuses and a `FLAKY` history, up to 6 occurrences); `FailureRulesTest.int002RefusesAProductRuleThatCouldMatchAnInfrastructureFault` (the API refuses to start with such a table). API: `FailureGroupApiTest.int002DifferentFailuresAreDifferentGroupsWithTheirOwnLabel`, `…int002ABrowserLaunchFaultIsAnAttemptLevelEnvironmentGroupNeverAProductDefect`. **Live:** the six scenarios give 7 groups and no group holds two scenarios (`PRODUCT_DEFECT` `31e12582bd8cb543`, `AUTOMATION_FLAKE` `ba36edd66a8ba130`, `SELECTOR_DRIFT` `7ca188d136378a4a`, `TIMEOUT` `933b698a822935d9`, and `BROWSER_LAUNCH` one group per engine, because each engine's missing executable path is different). All 15 `BROWSER_LAUNCH` targets are `INFRA_ERROR` and `ENVIRONMENT` (R3), and no row outside `PRODUCT_DEFECT` is `PRODUCT_SUSPECTED`, after a run or at the end, in either run (`data/failure-eval.md`, `raw/prd-07-eval-three-engines-run-1.md`). |
| AC-3 | Yes | INT-003 | API: `CaseHealthApiTest.int003AnAlternatingCaseIsLabelledFlakyAndItsGroupBecomesAnAutomationFlake`, `FailureGroupApiTest.int003AnOverrideChangesTheCategoryIsAuditedAndSurvivesNewOccurrences`, `FailureGroupListApiTest.filtersByShownCategoryEngineAndLastSeen` (the list filters on the override). Web: `FailureGroupPage.test.tsx` "saves an override with its reason and shows the new category and audit entry". **Live:** after the `AUTOMATION_FLAKE` fixture ran with 5 hashed seeds, `checkout-happy-path` is `FLAKY` on every engine with the reason "2 passed only on a retry (≥ 2)" (`data/failure-eval.md`, AC-3 table), and its group's evidence names the case. **`raw/prd-07-ac3-override-live.txt`**: `PUT /failure-groups/{id}/override` changes the shown `category` to `PRODUCT_SUSPECTED` while `ruleCategory` stays `AUTOMATION_FLAKE`, writes one `FAILURE_CATEGORY_OVERRIDDEN` audit row (actor `local`, N26), a repeat changes nothing, and the list now files the group under `PRODUCT_SUSPECTED`. In T5 the same flow was also clicked through in the dashboard. |
| AC-4 | Yes | CMP-001, CMP-002 | Unit: `RunComparisonTest.cmp001CartTotalIsANewFailureOnEveryEngineAndEverythingElseStillPasses`, `…cmp002ACaseTwiceAsSlowIsARegressionOnlyPastBothThresholds` (2× and +500 ms are both strict), `largeRunsCompareKeyByKey` (O(n)). API: `RunCompareApiTest.cmp001APassRunAgainstAProductDefectRunHasCartTotalAsTheOnlyNewFailure` (3 `NEW_FAILURE`, 15 `STILL_PASSING`, every other count 0), `…cmp002ACaseTwiceAsSlowInHeadIsADurationRegression` (1200 → 2400 ms flagged on every engine). Web: `ComparePage.test.tsx` "calls out a changed fingerprint and a duration regression". **Live:** **`raw/prd-07-ac4-compare-live.txt`**, on a fresh project, a `PASS` run (base) against a `PRODUCT_DEFECT` run (head), 3 engines each: `cart-total` is `NEW_FAILURE` on chromium, firefox and webkit, and the other 15 rows are `STILL_PASSING` (every other count 0). `base=last-green` picks the same `PASS` run (`baseIsLastGreen: true`). `cart-total` is also flagged as a duration regression (912 → 11,496 ms on chromium) because the failing case ran its Playwright retry; the 2× case of CMP-002 is proven by the two tests above, not live. `raw/prd-07-compare-after-eval-limitation.txt` keeps the same comparison made after the whole evaluation, where `checkout-happy-path` shows as `FLAKY_IN_HEAD` (see the limitations). |
| AC-5 | Yes | (FR-8) | `make eval-failures EVAL_ARGS="--runs 5 --engines chromium,firefox,webkit"` on a fresh stack at commit `3b8d775` (clean tree) wrote **`data/failure-eval.csv`** (108 raw rows, one per failure occurrence or failure-free run, with the rule, its confidence and its reason at that moment) and **`data/failure-eval.md`** (two confusion matrices, precision and recall per label, the misclassified rows, the AC-3 health table and the run log). Earlier runs are kept in `raw/`: the first three-engine run (`raw/prd-07-eval-three-engines-run-1.*`), and the two runs that found N30 and N31. The published analysis is in "Classifier evaluation (T7)" below. |

## Classifier evaluation (T7)

**How to read it.** Each row is one failure occurrence: a case result with a fingerprint, or a whole attempt that failed to run. A run with no failure at all gives one `NONE` row. The ground truth is the scenario's label (`PASS` → `NONE`, `PRODUCT_DEFECT` → `PRODUCT_SUSPECTED`, `AUTOMATION_FLAKE` → `AUTOMATION_FLAKE`, `SELECTOR_DRIFT` → `TEST_BROKEN`, `TIMEOUT` → `TIMEOUT`, `BROWSER_LAUNCH` → `ENVIRONMENT`). The prediction is the rule's own label (`ruleCategory`), never an override. **After run** is the label a user saw right after that run finished. **Final** is the group's label once all 30 runs had finished. The numbers to judge the classifier by are the after-run ones: final labels are wiser than anything a user saw at the time.

**Runs.**

| run | files | commit | rows | correct after run | correct final |
|---|---|---|---:|---:|---:|
| 2 (published) | `data/failure-eval.{csv,md}` | `3b8d775`, clean when it started¹ | 108 | 104 (96.3 %) | 107 (99.1 %) |
| 1 | `raw/prd-07-eval-three-engines-run-1.*` | `d84b32b` plus the then-uncommitted T6 code, committed unchanged as `0df662a`–`03e6914` | 107 | 103 (96.3 %) | 106 (99.1 %) |
| seeds 1–5 | `raw/prd-07-eval-seeds-1-5.*` | before N30 | 100 | 92 | 95 |
| large seeds | `raw/prd-07-eval-large-seed-bug.*` | before N31 | — | — | — |

¹ Run 2's report says "with uncommitted changes" because it read `git status` when it finished, after I had edited `docs/CONFLICT_REGISTER.md` and this file during the run. No code changed. `d772aac` now records the state when the run starts. The last two runs found harness and fixture bugs (N30, N31) and are kept as evidence, not counted.

### Confusion matrix, run 2, label after each run

| expected ↓ / predicted → | PRODUCT_SUSPECTED | TEST_BROKEN | AUTOMATION_FLAKE | TIMEOUT | ENVIRONMENT | UNKNOWN | NONE | total |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| **PRODUCT_SUSPECTED** | **12** | 0 | 3 | 0 | 0 | 0 | 0 | 15 |
| **TEST_BROKEN** | 0 | **30** | 0 | 0 | 0 | 0 | 0 | 30 |
| **AUTOMATION_FLAKE** | 0 | 0 | **12** | 0 | 0 | 0 | 1 | 13 |
| **TIMEOUT** | 0 | 0 | 0 | **30** | 0 | 0 | 0 | 30 |
| **ENVIRONMENT** | 0 | 0 | 0 | 0 | **15** | 0 | 0 | 15 |
| **NONE** | 0 | 0 | 0 | 0 | 0 | 0 | **5** | 5 |

### Precision and recall per category, run 2

| category | precision after run | recall after run | precision final | recall final | rows |
|---|---:|---:|---:|---:|---:|
| PRODUCT_SUSPECTED | 1.00 (12/12) | 0.80 (12/15) | 1.00 (15/15) | 1.00 (15/15) | 15 |
| TEST_BROKEN | 1.00 (30/30) | 1.00 (30/30) | 1.00 | 1.00 | 30 |
| AUTOMATION_FLAKE | 0.80 (12/15) | 0.92 (12/13) | 1.00 (12/12) | 0.92 (12/13) | 13 |
| TIMEOUT | 1.00 (30/30) | 1.00 (30/30) | 1.00 | 1.00 | 30 |
| ENVIRONMENT | 1.00 (15/15) | 1.00 (15/15) | 1.00 | 1.00 | 15 |
| NONE (no failure) | 0.83 (5/6) | 1.00 (5/5) | 0.83 | 1.00 | 5 |
| UNKNOWN | n/a | n/a | n/a | n/a | 0 |

The final matrix differs only in the `PRODUCT_SUSPECTED` row (15 / 0 / 0), see `data/failure-eval.md`. Run 1 has the same matrices except `TEST_BROKEN` 29 of 29 (N32, below). Both runs gave the same fingerprints for every scenario and the same four misclassified rows, so the results repeat.

**What the numbers say, and don't.** Infrastructure faults, timeouts and selector drift are labelled correctly every time, from the first occurrence. Their rules read the error class alone, and the error class is reliable. The two assertion categories are where the rules have to judge, and all four errors are there. AC-2's hard line holds: across 215 rows in two runs, no infrastructure fault and no other scenario was ever `PRODUCT_SUSPECTED`. The samples are small (13–30 rows a category), so one row moves recall by up to 0.08, and nothing here supports a claim like "96 % accurate" on other suites.

### Known ambiguous cases

1. **A new product defect looks flaky for one run** (3 rows, both runs, after run only). Five `PASS` runs, then `cart-total` fails, give the window `PPPPPF`: n = 6, 1 transition, `flip_rate` = 1/5 = 0.20. Both outcomes are present and 0.20 meets the inclusive 0.2 threshold (FR-4), so `case_health` says `FLAKY`, and R4 (`caseHealthFlaky`) wins over R5. The label is shown with a low confidence, 0.34 (0.60 + 0.02 × 2 − 0.30 × 1: no occurrence passed on a retry, so the history is the only evidence), and the misclassified-rows table shows the reason it gave. The next failure fixes it: `PPPPPFF` has `flip_rate` 1/6 = 0.17, no longer `FLAKY`, and R5 labels the group `PRODUCT_SUSPECTED` (0.80 by the end). This one cause accounts for all of the `PRODUCT_SUSPECTED` recall loss and 3 of the `AUTOMATION_FLAKE` false positives. Possible fixes, none applied because each changes FR-4 or FR-2 and needs a ruling: make the threshold strict (`>` 0.2), require at least two failures in the window before flips count, or ignore history in R4 until a group has been seen in two runs. Each one delays flake detection by a run.
2. **A flake run that never failed** (1 row, both runs). `AUTOMATION_FLAKE` run 3 (seed 1520856342) passed on every engine at the first try, because the fixture's delay is random by design. There was nothing to label, so the row is `NONE`, which costs `AUTOMATION_FLAKE` recall (0.92) and `NONE` precision (0.83). It is kept in the data on purpose: dropping it would make the table look better than what the user experiences.
3. **The mirror image of case 1, not seen here.** If a flake's first appearance fails both tries on two engines, the group has 2 attempts, no retry pass and no `FLAKY` history yet, so R5 says `PRODUCT_SUSPECTED`. It didn't happen in these runs (run 1 of the flake passed on a retry), but nothing prevents it. Only history can tell the two apart, which is why the label says "likely", the confidence and facts are shown, and a human can override.
4. **The selector-drift fixture leaks (N32, open).** The cart page shows the undrifted test id until `/api/ui-config` loads, so a fast click can succeed. Seen 3 times, always on firefox. In run 1 a case passed on its first try (no failure, so 29 rows instead of 30). Twice a case passed on its retry: it is still a `TEST_BROKEN` row, because the first try failed, but the case's health records a pass. The classifier is right each time; the ground truth isn't what the fixture promises.
5. **One environment fault, three groups.** The `BROWSER_LAUNCH` message names the browser executable that is missing (`…/chromium_headless_shell-…`, `…/firefox-…`, `…/webkit-…`), so `fp_v1` gives one group per engine. Each is correctly `ENVIRONMENT`, and none is merged with anything else (AC-2). A future `fp_v2` could normalise Playwright's browser directories if one group is wanted.
6. **`FLAKY` from retries, not flips.** The AC-3 window `PPPPPKKPFFFFFFFTTTTT` also holds the later drift (F) and timeout (T) runs, because case health counts every run of the case. Its `flip_rate` is only 0.053. The label is `FLAKY` because of the second clause: two results passed only on a retry (K ≥ 2).
7. **`FLAKY_IN_HEAD` reads today's label.** The same base and head compared after the whole evaluation show `checkout-happy-path` as `FLAKY_IN_HEAD` rather than `STILL_PASSING` (`raw/prd-07-compare-after-eval-limitation.txt`), because the flake scenario that ran later labelled it `FLAKY`. On a fresh project the comparison is exactly AC-4 (`raw/prd-07-ac4-compare-live.txt`).
8. **`UNKNOWN` (R6) has no live ground truth.** No scenario produces an unmatched failure, so it is tested only in `FailureClassifierTest` and `FailureRulesTest`.

## Tasks completed

| Task | Commit(s) | Notes |
|---|---|---|
| T1 | `27ad0f2`, `d21232f` | `fp_v1` in Java (N28): strip ANSI and control characters, cut at `Call log:` (N24), drop query strings, replace `<UUID>`, `<TS>`, `<TMP>`, `<HEX>`, `<N>`, collapse spaces, repeat until nothing changes (idempotent by construction). `sha256("fp_v1" \| class \| frames \| message)[0:16]`. The worker sends up to 3 `file:function` frames from Playwright's stack (N24); the API validates their shape. |
| T2 | `388b897`, `226c16a`, `04822fb`, `b94c283` | V11 `failure_fingerprints`, `audit_events` (append-only, N26) and `attempts.fingerprint_id` (N25). `failure-rules.yaml` R1–R6, validated on start. Confidence = base + 0.02 × min(n − 1, 5) − 0.30 × (1 − agreement), within 0.05 … 0.95. Every failure is grouped and classified inside the completion transaction. `GET /failure-groups/{id}`, `PUT …/override` (redacted reason, audited, idempotent). |
| T3 | `e86f66f`, `bf9a5b3`, `ef9a77f`, `162f35d` | V12 `case_health`: a P/K/F/T window of 20 per (suite, case, engine), updated incrementally at completion under row locks (fixed lock order), labels and reasons; R4 reads the `FLAKY` label. `GET /cases/{caseKey}/history` in one `REPEATABLE READ` snapshot; the case history page with an outcome strip and a duration sparkline, linked from the case drawer. |
| T4 | `b3e9917`, `727c0d0`, `3c11439` | O(n) comparison keyed by (caseKey, engine), nine categories, regressions past ratio 1.5 and +500 ms, `fingerprintChanged`. `GET /runs/compare` with `last-green`; `422 incomparable-runs` for different suites. The compare page and "Compare with last green" on run detail. |
| T5 | `248fdd5`, `b07a108` | `GET /failure-groups` (shown-category, engine and last-seen filters, keyset cursor over count and id, V13 index) and occurrence artifacts. `/failures` list and `/failures/:groupId` detail with the rule, its evidence, the occurrences, the raw examples, the artifacts and the override form. |
| T6 | `0df662a`, `a069ef8`, `dccfeb4`, `4da938b`, `03e6914` | `TG_FAULT_WORKER=browser_launch` (N27); results carry each result's group; `scripts/eval-failures.ts` and `make eval-failures`, hashed seeds (N30); the spec support takes every seed the API takes (N31). |
| T7 | `3b8d775`, `d772aac` and the evidence commit that adds this file | The report lists each misclassified row with the rule's reason at that moment; a clean rerun; the analysis and known ambiguous cases below; N32. |

## Changed files

- `api/src/main/java/dev/testgrid/api/failure/*` (new): `FingerprintV1`, `FailureRules` and `FailureRulesConfig`, `FailureClassifier`, `FailureRepository`, `FailureRecorder`, `FailureGroupService`, `FailureGroupController`, `FailureGroupView`, `FailureGroupSummary`, `FailureGroupCursor`, `OverrideRequest`.
- `api/src/main/java/dev/testgrid/api/casehealth/*` (new): window, properties, repository, recorder, history service, view and controller.
- `api/src/main/java/dev/testgrid/api/compare/*` (new): `RunComparison`, its service, view, properties and controller.
- `api/src/main/java/dev/testgrid/api/audit/AuditRepository.java`, `domain/FailureCategory.java` (new); `problem/ProblemType.java` (`incomparable-runs`).
- `api/src/main/java/dev/testgrid/api/internal/WorkerApi.java`, `AttemptService.java`, `AttemptRepository.java`: `errorFrames`, fingerprinting, case health and classification in the completion transaction.
- `api/src/main/java/dev/testgrid/api/run/RunResults.java`, `RunRepository.java`, `artifact/ArtifactRepository.java`: each result's group; artifacts by attempt.
- `api/src/main/resources/db/migration/V11__failure_fingerprints.sql`, `V12__case_health.sql`, `V13__failure_group_list.sql`; `failure-rules.yaml`; `application.properties`.
- `api/openapi.yaml`, `packages/contracts/src/api.ts`: regenerated.
- API tests: `FingerprintV1Test`, `FailureRulesTest`, `FailureClassifierTest`, `CaseHealthWindowTest`, `RunComparisonTest`, `CompletionFramesTest`, `FailureGroupApiTest`, `FailureGroupListApiTest`, `CaseHealthApiTest`, `RunCompareApiTest` (new); `LeaseProtocolTest` and the test bases (new tables in the TRUNCATE lists).
- `worker/src/frames.ts` (new), `report.ts`, `attempt.ts`, `config.ts`, `main.ts`; `worker/suites/support/fixture-env.ts`, `shop-test.ts` (N31); tests and the fake CLI.
- `web/src/failures/*`, `web/src/compare/*`, `web/src/cases/*` (new); `web/src/api/client.ts`, `queries.ts`, `types.ts`; `app/routes.tsx`, `AppShell.tsx`; `runs/detail/CaseDrawer.tsx`, `RunHeader.tsx`, `RunDetailPage.tsx`; tests and fixtures.
- `scripts/eval-failures.ts`, `scripts/tsconfig.json` (new); `Makefile` (`eval-failures`, the scripts type-check in `make check`); `.env.example`.
- Docs: `docs/API_AND_DATA_CONTRACTS.md` (§3 tables, §4 endpoints, §6 `incomparable-runs`, §7 `errorFrames`, §8 comparison, §8.1 `fp_v1`, §8.2 classifier, §8.3 case health), `docs/CONFLICT_REGISTER.md` (N24–N32), `docs/SECURITY_AND_TEST_STRATEGY.md`, `docs/RUNBOOK.md`; evidence data and raw runs.

## Actual executed commands and outputs

```text
$ make check
… prettier --check / eslint --max-warnings=0 / tsc (demo-shop, worker, web, contracts)
pnpm exec tsc -p scripts/tsconfig.json
cd api && ./mvnw -B -q spotless:check compile
make check exit 0

$ make test
demo-shop test: ℹ tests 42   ℹ fail 0
worker test:    ℹ tests 111  ℹ fail 0
web test:       Test Files 19 passed (19)   Tests 272 passed (272)
api (surefire): 79 classes, tests=536, failures=0, errors=0, skipped=0
make test exit 0
(baseline at the end of PRD-06: api 468, worker 102, web 245, demo-shop 42)

$ make contracts-check
contracts-check OK: api/openapi.yaml and packages/contracts/src/api.ts match the code

$ bash tools/vocab_check.sh
Non-canonical spellings: none
vocab_check OK

$ python3 tools/progress.py lint
lint OK: tracker matches PRD files

$ COMPOSE_PROJECT_NAME=testgrid-eval make reset          # a separate Compose project; the developer's testgrid_* volumes untouched
all services healthy in 20s
  api OK · web OK · demo-shop OK {"fixtures":"enabled"}

$ COMPOSE_PROJECT_NAME=testgrid-eval make eval-failures EVAL_ARGS="--runs 5 --engines chromium,firefox,webkit"
PASS #1 (seed 506952114) PASSED in 19 s: -:NONE
…
PRODUCT_DEFECT #1 (seed 506952114) FAILED in 44 s: cart-total:AUTOMATION_FLAKE ×3
PRODUCT_DEFECT #2 (seed 1013904228) FAILED in 44 s: cart-total:PRODUCT_SUSPECTED ×3
…
AUTOMATION_FLAKE #3 (seed 1520856342) PASSED in 17 s: -:NONE
…
SELECTOR_DRIFT #5 (seed 387276923) FAILED in 48 s: card-declined:TEST_BROKEN checkout-happy-path:TEST_BROKEN ×3
TIMEOUT #5 (seed 387276923) TIMED_OUT in 137 s: card-declined:TIMEOUT checkout-happy-path:TIMEOUT ×3
BROWSER_LAUNCH: recreating the worker with TG_FAULT_WORKER=browser_launch
BROWSER_LAUNCH #5 (seed 387276923) INFRA_ERROR in 7 s: (whole target):ENVIRONMENT ×3
BROWSER_LAUNCH: putting the worker back without a fault
wrote docs/evidence/data/failure-eval.csv and failure-eval.md
correct after run 104/108, final 107/108
exit 0
(full run log: the "Runs" section of data/failure-eval.md; 2026-11-24 13:40:41–14:04:48 UTC)

$ python3 ac3-capture.py     → docs/evidence/raw/prd-07-ac3-override-live.txt (exit 0; the UPDATE on audit_events exits 1, as it must)
$ COMPOSE_PROJECT_NAME=testgrid-eval make reset && python3 ac4.py     → docs/evidence/raw/prd-07-ac4-compare-live.txt (exit 0)
```

## Negative / failure tests

- **Infrastructure fault as a product defect.** Injected: every infrastructure error class with every status, retry count and history (`FailureClassifierTest.int002…`), a rule table that would allow it (`FailureRulesTest.int002…`: the API refuses to start), and a real `TG_FAULT_WORKER=browser_launch` worker (live, 15 targets). Observed: never `PRODUCT_SUSPECTED`; `ENVIRONMENT` via R3.
- **Hostile frames.** Injected: frames with line numbers, absolute paths, `..`, `|` or `;` separators, more than 3, frames on a passing case, frames without an error class (`FingerprintV1Test.refusesFrames…`, `aFrameCanNotMoveTheBoundaryIntoTheMessage`, `CompletionFramesTest`). Observed: `400`/`422` before anything is stored; no frame can shift the hash boundary.
- **A secret in an error message.** Injected: a bearer token in `errorMessage` (`FailureGroupApiTest.aSecretIsRedactedBeforeTheMessageIsNormalised`). Observed: redacted before it is normalised, hashed or stored.
- **A bad override or query.** Unknown category, blank or 501-character reason, unknown group, unknown or repeated parameters, bad cursor, reversed time range (`FailureGroupApiTest.badOverridesAre400…`, `FailureGroupListApiTest.badListQueriesAre400…`, `CaseHealthApiTest.badHistoryQueries…`, `RunCompareApiTest.runsOfDifferentSuitesAre422AndBadQueriesAre400`). Observed: `400`, `404` or `422` problems, nothing written.
- **Concurrent completions on the same group and case.** Covered by the lock order (run → fingerprint rows sorted by value → `case_health` rows sorted by key) and `CaseHealthApiTest.theIncrementalWindowMatchesARecountOfHistory…`, which rebuilds the window from history and compares. `CaseHealthWindowTest.incrementalAppendsAgreeWithTheReferenceOnRandomHistories` checks the incremental maths against a recount.
- **An infra-error target in a comparison.** `RunCompareApiTest.anInfraErrorTargetIsNotComparableAndTheOtherEnginesStillCompare`: the firefox target's 6 cases are `NOT_COMPARABLE`, and chromium and webkit still compare.
- **Ground-truth faults found live:** the seed spread (N30), seeds ≥ 10⁹ (N31) and the selector-drift race (N32), each recorded with its raw run.

## Security and threat check

- **Input restrictions checked:** frames are `file:function` only (regex, ≤ 3, no line numbers, no absolute or `..` paths); the hash input is built by the API, never sent by the worker. Every new query parameter is allowlisted, typed and bounded (`limit` 1–100, enums, ISO instants, UUIDs, opaque versioned cursors). The override reason is 1–500 characters and redacted before it is stored and audited. The rule table is a server resource read with SnakeYAML's `SafeConstructor` and strict field mapping. No client-supplied regex, URL, code or Playwright flag (ADR-003). `TG_FAULT_WORKER=browser_launch` is refused outside `TESTGRID_PROFILE=local`.
- **Secret or artifact leak scan (canary):** error messages pass through `Redactor` before normalisation, so the stored `normalised_message`, the raw examples and the evidence never hold a secret (`FailureGroupApiTest.aSecretIsRedactedBeforeTheMessageIsNormalised`). `FailureGroupListApiTest` checks that no `storageKey` reaches the occurrence artifacts. `make scan-canary` (PRD-06) was not rerun for PRD-07.
- **New attack surface introduced, and its mitigation:** four read endpoints and one write (`PUT …/override`). Until PRD-12 there is no authentication, so anyone who can reach the loopback-bound public port can override a category. The mitigation for now is the append-only audit trail (`audit_events` refuses UPDATE and DELETE by trigger) and that the rule's own label is always kept and shown (`ruleCategory`). `audit_events.actor` is `local` until PRD-12 (N26).

## Skipped or mocked tests

- Worker unit tests (`frames.test.ts`, `report.test.ts`, `attempt.test.ts`, `config.test.ts`) use the recorded Playwright 1.65.1 report and the fake CLI (`replay.mjs` simulates a launch failure). **Mocked.** The real launch failure is covered live by the evaluation's BROWSER_LAUNCH scenario.
- Web tests (vitest, Testing Library, axe in jsdom) use fixture API responses. **Mocked.** The failure group pages and the override were also clicked through on the live stack in T5. No new Playwright E2E for the PRD-07 pages: the dashboard E2E from PRD-05 still runs in CI, but it doesn't visit `/failures`, `/compare` or `/cases`.
- The evaluation, the AC-3 capture and the AC-4 capture: **live**.
- GitHub Actions: **not run**, because the branch is not pushed.

## Screenshots / demo / raw data links

- `docs/evidence/data/failure-eval.csv`, `docs/evidence/data/failure-eval.md`: the published evaluation (run 2, 2026-11-24 13:40–14:05 UTC, commit `3b8d775`).
- `docs/evidence/raw/prd-07-eval-three-engines-run-1.csv`, `.md`: the first three-engine run, for repeatability.
- `docs/evidence/raw/prd-07-eval-seeds-1-5.csv`, `.md`: seeds 1–5, no flake ever triggered (N30).
- `docs/evidence/raw/prd-07-eval-large-seed-bug.csv`, `.md`: every seed ≥ 10⁹ ended `INFRA_ERROR` (N31).
- `docs/evidence/raw/prd-07-ac3-override-live.txt`: AC-3 override and audit on the live stack.
- `docs/evidence/raw/prd-07-ac4-compare-live.txt`: AC-4 comparison on the live stack.

## Known limitations and deferred improvements

- **Labels are likely, not proven.** The UI always shows the rule, its reason, the facts and the confidence, and lets a human override. The ambiguous cases above are where the rules are known to be wrong for a while.
- **A group is relabelled only when it gets a new occurrence** (contracts §8.2). If a case's health changes later with no new failure, the group keeps its old label until it fails again.
- **`FLAKY_IN_HEAD` uses the case's current health label**, not the label at the time of the head run, so comparing two old runs can show a later flakiness (`RunCompareApiTest.lastGreen…` documents it).
- **Attempts abandoned by the reaper (`LEASE_EXPIRED`) are not grouped**: they have no completion, so no failure message to fingerprint.
- **Case health counts every run on any branch**, including the evaluation's own runs: a `PRODUCT_DEFECT` run after five `PASS` runs flips the window. Per-branch health is a later improvement.
- **The selector-drift fixture race (N32)** is open and needs a ruling, because the fix is in PRD-01's demo shop.
- **No `UNKNOWN` ground truth:** none of the six scenarios exercises R6, so its precision and recall are not measured live (unit-tested only).
- **Small samples:** 5 runs per scenario and 13–30 rows per label, so one row moves recall by up to 0.08.
- Deferred: an `fp_v2` (the table already keys on `algo_version`); a background relabel when health changes; per-branch health; Playwright E2E for the new pages.

## Explain-back review (developer's own words)

> Not answered yet. PRD-07's definition of done says "the developer can explain normalisation, the flaky window and the comparison complexity without AI". The developer should answer these without notes before `tools/progress.py done PRD-07`. Claude can provide an answer key afterwards to check against.

1. The three most important engineering decisions in this PRD, and why.
2. What happens if the main operation fails halfway through? For example, the API dies inside the completion transaction after the fingerprint row was upserted but before `case_health` was written, or two workers complete attempts with the same failure at the same moment.
3. What would you change for production?
4. **Normalisation:** take `Error: order 48213 failed at 2026-11-24T10:00:00Z (/tmp/pw-abc/x.png?sig=1)\nCall log:\n  - 3 × locator resolved`. Write down what `fp_v1` turns it into, step by step. Why must the `Call log:` cut come first, and why does the normaliser loop until nothing changes?
5. **Hashing:** why is the error class and each frame part of the hash input, separated by `|` and `;`, and what stops a worker from sending a frame that shifts that boundary? Why keep `algo_version` in the table?
6. **The flaky window:** for the window `PPPPPF`, compute n, the transitions, `flip_rate` and the label. Why did that make the first live `PRODUCT_DEFECT` run look like an `AUTOMATION_FLAKE`, why was its confidence only 0.34, and what single change to the rules would avoid it (and what would it cost)?
7. **Incremental update:** why is the window updated in the completion transaction rather than recomputed from history, and why are the `case_health` rows locked in sorted key order?
8. **Comparison complexity:** why is the comparison O(n) and not O(n²)? What is the key, what happens to a case that only one side has, and why is a duration regression "> 1.5× **and** > +500 ms" rather than either one?
9. **Evaluation:** why does the harness use hashed seeds rather than 1–5, why does it refuse a project that already has runs, and why does it score `ruleCategory` and never the shown category?

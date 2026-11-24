package dev.testgrid.api.quality;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.QualityOutcome;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.quality.QualityPolicy.AutoMode;
import dev.testgrid.api.quality.QualityPolicy.DurationRegression;
import dev.testgrid.api.quality.QualityPolicy.QuarantineSettings;
import dev.testgrid.api.quality.QualityRules.CaseFact;
import dev.testgrid.api.quality.QualityRules.DurationFacts;
import dev.testgrid.api.quality.QualityRules.Evaluation;
import dev.testgrid.api.quality.QualityRules.QuarantineFact;
import dev.testgrid.api.quality.QualityRules.Regression;
import dev.testgrid.api.quality.QualityRules.RuleResult;
import dev.testgrid.api.quality.QualityRules.RunFacts;
import dev.testgrid.api.quality.QualityRules.TargetFact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** PRD-08 FR-2 policy unit matrix, boundary equality included (contracts §2.4, §12). */
class QualityRulesTest {

  private static final Instant EXPIRY = Instant.parse("2026-12-01T00:00:00Z");
  private static final UUID LAST_GREEN = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

  // ---- builders ----------------------------------------------------------------------------

  private static QualityPolicy policy() {
    return new QualityPolicy(
        List.of("local:chromium"),
        true,
        0,
        true,
        0.2,
        new DurationRegression(true, 1.5, 500, false),
        new QuarantineSettings(true, 5, AutoMode.SUGGEST));
  }

  private static QualityPolicy with(
      List<String> required, boolean critical, int maxFailed, boolean infra, double flaky) {
    QualityPolicy p = policy();
    return new QualityPolicy(
        required, critical, maxFailed, infra, flaky, p.durationRegression(), p.quarantine());
  }

  private static QualityPolicy withDuration(boolean enabled, boolean blocking) {
    QualityPolicy p = policy();
    return new QualityPolicy(
        p.requiredTargets(),
        p.failOnCriticalCase(),
        p.maxFailedCases(),
        p.failOnInfraError(),
        p.maxFlakyRate(),
        new DurationRegression(enabled, 1.5, 500, blocking),
        p.quarantine());
  }

  private static QualityPolicy withHonor(boolean honor) {
    QualityPolicy p = policy();
    return new QualityPolicy(
        p.requiredTargets(),
        p.failOnCriticalCase(),
        p.maxFailedCases(),
        p.failOnInfraError(),
        p.maxFlakyRate(),
        p.durationRegression(),
        new QuarantineSettings(honor, 5, AutoMode.SUGGEST));
  }

  private static TargetFact target(Engine engine, TargetStatus status, boolean results) {
    return target(engine, 0, status, results, null);
  }

  private static TargetFact target(
      Engine engine, int shard, TargetStatus status, boolean results, ErrorClass error) {
    return new TargetFact(
        "local:" + engine.wireName() + ":" + shard, Executor.LOCAL, engine, status, results, error);
  }

  private static CaseFact result(Engine engine, String caseKey, CaseStatus status) {
    return new CaseFact(
        "local:" + engine.wireName() + ":0", engine, caseKey, status, caseKey.equals("cart-total"));
  }

  /** The six checkout-smoke cases on one engine, all PASSED except the given overrides. */
  private static List<CaseFact> checkout(Engine engine, Object... overrides) {
    List<String> keys =
        List.of(
            "add-to-cart",
            "apply-coupon",
            "browse-catalog",
            "card-declined",
            "cart-total",
            "checkout-happy-path");
    List<CaseFact> out = new ArrayList<>();
    for (String key : keys) {
      CaseStatus status = CaseStatus.PASSED;
      for (int i = 0; i < overrides.length; i += 2) {
        if (overrides[i].equals(key)) {
          status = (CaseStatus) overrides[i + 1];
        }
      }
      out.add(result(engine, key, status));
    }
    return out;
  }

  private static RunFacts facts(
      RunStatus run, List<TargetFact> targets, List<CaseFact> cases, DurationFacts duration) {
    return new RunFacts(run, targets, cases, duration);
  }

  private static RunFacts chromium(TargetStatus status, List<CaseFact> cases) {
    RunStatus run =
        switch (status) {
          case PASSED -> RunStatus.PASSED;
          case FAILED -> RunStatus.FAILED;
          case TIMED_OUT -> RunStatus.TIMED_OUT;
          default -> RunStatus.INFRA_ERROR;
        };
    return facts(
        run,
        List.of(target(Engine.CHROMIUM, status, !cases.isEmpty())),
        cases,
        new DurationFacts(LAST_GREEN, List.of()));
  }

  private static RuleResult rule(Evaluation e, String id) {
    return e.rules().stream().filter(r -> r.ruleId().equals(id)).findFirst().orElseThrow();
  }

  private static QuarantineFact quarantine(String caseKey, Engine engine) {
    return new QuarantineFact(UUID.randomUUID(), caseKey, engine, EXPIRY);
  }

  // ---- tests -------------------------------------------------------------------------------

  @Test
  void ci001AGreenRunPassesEveryRule() {
    Evaluation e =
        QualityRules.evaluate(
            policy(), chromium(TargetStatus.PASSED, checkout(Engine.CHROMIUM)), List.of());
    assertThat(e.outcome()).isEqualTo(QualityOutcome.PASS);
    assertThat(e.rules())
        .extracting(RuleResult::ruleId)
        .containsExactly(
            "required-targets",
            "infra-error",
            "critical-cases",
            "max-failed-cases",
            "max-flaky-rate",
            "duration-regression",
            "quarantine-honored");
    assertThat(e.rules()).allMatch(r -> r.outcome() == QualityOutcome.PASS);
    assertThat(rule(e, "required-targets").observed()).isEqualTo(1);
    assertThat(rule(e, "required-targets").threshold()).isEqualTo(1);
  }

  @Test
  void ci002ACriticalCaseFailureFailsAndTheEvidenceNamesCaseAndEngine() {
    Evaluation e =
        QualityRules.evaluate(
            policy(),
            chromium(
                TargetStatus.FAILED, checkout(Engine.CHROMIUM, "cart-total", CaseStatus.FAILED)),
            List.of());
    assertThat(e.outcome()).isEqualTo(QualityOutcome.FAIL);
    RuleResult critical = rule(e, "critical-cases");
    assertThat(critical.outcome()).isEqualTo(QualityOutcome.FAIL);
    assertThat(critical.blocking()).isTrue();
    assertThat(critical.observed()).isEqualTo(1);
    assertThat(critical.evidence()).hasSize(1);
    assertThat(critical.evidence().getFirst().caseKey()).isEqualTo("cart-total");
    assertThat(critical.evidence().getFirst().engine()).isEqualTo(Engine.CHROMIUM);
    assertThat(critical.evidence().getFirst().status()).isEqualTo("FAILED");
    assertThat(critical.evidence().getFirst().counted()).isTrue();
  }

  @Nested
  class MaxFailedCases {

    private Evaluation failures(int max, int failed) {
      Object[] overrides = new Object[failed * 2];
      String[] nonCritical = {"add-to-cart", "apply-coupon", "browse-catalog", "card-declined"};
      for (int i = 0; i < failed; i++) {
        overrides[i * 2] = nonCritical[i];
        overrides[i * 2 + 1] = CaseStatus.FAILED;
      }
      return QualityRules.evaluate(
          with(List.of("local:chromium"), true, max, true, 1.0),
          chromium(TargetStatus.FAILED, checkout(Engine.CHROMIUM, overrides)),
          List.of());
    }

    @Test
    void zeroFailuresAgainstZeroPasses() {
      assertThat(failures(0, 0).outcome()).isEqualTo(QualityOutcome.PASS);
    }

    @Test
    void oneFailureAgainstZeroFails() {
      Evaluation e = failures(0, 1);
      assertThat(e.outcome()).isEqualTo(QualityOutcome.FAIL);
      assertThat(rule(e, "max-failed-cases").observed()).isEqualTo(1);
      assertThat(rule(e, "max-failed-cases").threshold()).isEqualTo(0);
    }

    @Test
    void failedEqualToMaxPasses() {
      Evaluation e = failures(2, 2);
      assertThat(rule(e, "max-failed-cases").outcome()).isEqualTo(QualityOutcome.PASS);
      assertThat(e.outcome()).isEqualTo(QualityOutcome.PASS);
    }

    @Test
    void oneOverMaxFails() {
      assertThat(failures(2, 3).outcome()).isEqualTo(QualityOutcome.FAIL);
    }

    @Test
    void aTimedOutCaseCountsAsAFailure() {
      Evaluation e =
          QualityRules.evaluate(
              with(List.of("local:chromium"), true, 0, true, 1.0),
              chromium(
                  TargetStatus.TIMED_OUT,
                  checkout(Engine.CHROMIUM, "add-to-cart", CaseStatus.TIMED_OUT)),
              List.of());
      assertThat(rule(e, "required-targets").outcome()).isEqualTo(QualityOutcome.PASS);
      assertThat(rule(e, "max-failed-cases").observed()).isEqualTo(1);
      assertThat(e.outcome()).isEqualTo(QualityOutcome.FAIL);
    }

    @Test
    void criticalCasesOffStillLeavesTheFailureCount() {
      QualityPolicy lenient = with(List.of("local:chromium"), false, 0, true, 1.0);
      RunFacts run =
          chromium(TargetStatus.FAILED, checkout(Engine.CHROMIUM, "cart-total", CaseStatus.FAILED));
      Evaluation e = QualityRules.evaluate(lenient, run, List.of());
      assertThat(rule(e, "critical-cases").blocking()).isFalse();
      assertThat(rule(e, "critical-cases").outcome()).isEqualTo(QualityOutcome.FAIL);
      assertThat(e.outcome()).isEqualTo(QualityOutcome.FAIL);

      Evaluation tolerant =
          QualityRules.evaluate(
              with(List.of("local:chromium"), false, 1, true, 1.0), run, List.of());
      assertThat(tolerant.outcome()).isEqualTo(QualityOutcome.PASS);
    }
  }

  @Nested
  class MaxFlakyRate {

    private Evaluation flaky(int flakyCount, double threshold, Object... extra) {
      List<Object> overrides = new ArrayList<>(List.of(extra));
      String[] keys = {"add-to-cart", "apply-coupon", "browse-catalog", "card-declined"};
      for (int i = 0; i < flakyCount; i++) {
        overrides.add(keys[i]);
        overrides.add(CaseStatus.FLAKY);
      }
      return QualityRules.evaluate(
          with(List.of("local:chromium"), true, 0, true, threshold),
          chromium(TargetStatus.PASSED, checkout(Engine.CHROMIUM, overrides.toArray())),
          List.of());
    }

    @Test
    void rateEqualToTheThresholdPasses() {
      // One SKIPPED leaves 5 executed results: 1/5 is exactly 0.2.
      Evaluation e = flaky(1, 0.2, "checkout-happy-path", CaseStatus.SKIPPED);
      assertThat(rule(e, "max-flaky-rate").observed()).isEqualTo(0.2);
      assertThat(rule(e, "max-flaky-rate").outcome()).isEqualTo(QualityOutcome.PASS);
    }

    @Test
    void rateAboveTheThresholdFails() {
      Evaluation e = flaky(2, 0.2, "checkout-happy-path", CaseStatus.SKIPPED);
      assertThat(rule(e, "max-flaky-rate").observed()).isEqualTo(0.4);
      assertThat(e.outcome()).isEqualTo(QualityOutcome.FAIL);
    }

    @Test
    void skippedResultsAreNotInTheDenominator() {
      Evaluation e = flaky(1, 0.2);
      assertThat(rule(e, "max-flaky-rate").observed()).isEqualTo(0.1667);
      assertThat(rule(e, "max-flaky-rate").evidence()).hasSize(1);
    }

    @Test
    void zeroThresholdFailsOnTheFirstFlake() {
      assertThat(flaky(0, 0.0).outcome()).isEqualTo(QualityOutcome.PASS);
      assertThat(flaky(1, 0.0).outcome()).isEqualTo(QualityOutcome.FAIL);
    }
  }

  @Nested
  class Indeterminate {

    @Test
    void ci003AMissingRequiredTargetIsIndeterminate() {
      Evaluation e =
          QualityRules.evaluate(
              with(List.of("local:chromium", "local:firefox"), true, 0, true, 0.2),
              chromium(TargetStatus.PASSED, checkout(Engine.CHROMIUM)),
              List.of());
      assertThat(e.outcome()).isEqualTo(QualityOutcome.INDETERMINATE);
      RuleResult required = rule(e, "required-targets");
      assertThat(required.observed()).isEqualTo(1);
      assertThat(required.threshold()).isEqualTo(2);
      assertThat(required.evidence())
          .anySatisfy(ev -> {
            assertThat(ev.target()).isEqualTo("local:firefox");
            assertThat(ev.counted()).isFalse();
          });
    }

    @Test
    void ci003AnInfraErrorTargetIsIndeterminateNeverPass() {
      RunFacts run =
          facts(
              RunStatus.INFRA_ERROR,
              List.of(
                  target(
                      Engine.CHROMIUM,
                      0,
                      TargetStatus.INFRA_ERROR,
                      false,
                      ErrorClass.BROWSER_LAUNCH)),
              List.of(),
              DurationFacts.noLastGreen());
      Evaluation e = QualityRules.evaluate(policy(), run, List.of());
      assertThat(e.outcome()).isEqualTo(QualityOutcome.INDETERMINATE);
      assertThat(rule(e, "infra-error").observed()).isEqualTo(1);
      assertThat(rule(e, "infra-error").evidence().getFirst().detail()).contains("BROWSER_LAUNCH");
      // Even with the infra rule off, the required target keeps it from passing (invariant I9).
      Evaluation lenient =
          QualityRules.evaluate(
              with(List.of("local:chromium"), true, 0, false, 0.2), run, List.of());
      assertThat(lenient.outcome()).isEqualTo(QualityOutcome.INDETERMINATE);
    }

    @Test
    void aTargetLevelTimeoutWithoutResultsIsIndeterminate() {
      Evaluation e =
          QualityRules.evaluate(policy(), chromium(TargetStatus.TIMED_OUT, List.of()), List.of());
      assertThat(e.outcome()).isEqualTo(QualityOutcome.INDETERMINATE);
      assertThat(rule(e, "required-targets").outcome()).isEqualTo(QualityOutcome.INDETERMINATE);
    }

    @Test
    void aCancelledRunIsIndeterminateEvenIfTheRequiredTargetPassed() {
      RunFacts run =
          facts(
              RunStatus.CANCELLED,
              List.of(
                  target(Engine.CHROMIUM, TargetStatus.PASSED, true),
                  target(Engine.FIREFOX, TargetStatus.CANCELLED, false)),
              checkout(Engine.CHROMIUM),
              DurationFacts.noLastGreen());
      Evaluation e = QualityRules.evaluate(policy(), run, List.of());
      assertThat(e.outcome()).isEqualTo(QualityOutcome.INDETERMINATE);
    }

    @Test
    void everyShardOfARequiredTargetMustBeConclusive() {
      RunFacts run =
          facts(
              RunStatus.INFRA_ERROR,
              List.of(
                  target(Engine.CHROMIUM, 0, TargetStatus.PASSED, true, null),
                  target(
                      Engine.CHROMIUM, 1, TargetStatus.INFRA_ERROR, false, ErrorClass.WORKER_LOST)),
              checkout(Engine.CHROMIUM),
              DurationFacts.noLastGreen());
      Evaluation e =
          QualityRules.evaluate(
              with(List.of("local:chromium"), true, 0, false, 0.2), run, List.of());
      assertThat(rule(e, "required-targets").observed()).isEqualTo(0);
      assertThat(e.outcome()).isEqualTo(QualityOutcome.INDETERMINATE);
    }

    @Test
    void anInfraErrorOnANonRequiredTargetOnlyBlocksWhenThePolicySaysSo() {
      RunFacts run =
          facts(
              RunStatus.INFRA_ERROR,
              List.of(
                  target(Engine.CHROMIUM, TargetStatus.PASSED, true),
                  target(Engine.FIREFOX, TargetStatus.INFRA_ERROR, false)),
              checkout(Engine.CHROMIUM),
              DurationFacts.noLastGreen());
      assertThat(QualityRules.evaluate(policy(), run, List.of()).outcome())
          .isEqualTo(QualityOutcome.INDETERMINATE);
      Evaluation lenient =
          QualityRules.evaluate(
              with(List.of("local:chromium"), true, 0, false, 0.2), run, List.of());
      assertThat(rule(lenient, "infra-error").outcome()).isEqualTo(QualityOutcome.INDETERMINATE);
      assertThat(rule(lenient, "infra-error").blocking()).isFalse();
      assertThat(lenient.outcome()).isEqualTo(QualityOutcome.PASS);
    }

    @Test
    void testEvidenceOfAFailureWinsOverAnInfraError() {
      RunFacts run =
          facts(
              RunStatus.FAILED,
              List.of(
                  target(Engine.CHROMIUM, TargetStatus.FAILED, true),
                  target(Engine.FIREFOX, TargetStatus.INFRA_ERROR, false)),
              checkout(Engine.CHROMIUM, "cart-total", CaseStatus.FAILED),
              DurationFacts.noLastGreen());
      Evaluation e =
          QualityRules.evaluate(
              with(List.of("local:chromium", "local:firefox"), true, 0, true, 0.2), run, List.of());
      assertThat(e.outcome()).isEqualTo(QualityOutcome.FAIL);
    }
  }

  @Nested
  class Duration {

    private RunFacts regressed() {
      return facts(
          RunStatus.PASSED,
          List.of(target(Engine.CHROMIUM, TargetStatus.PASSED, true)),
          checkout(Engine.CHROMIUM),
          new DurationFacts(
              LAST_GREEN,
              List.of(
                  new Regression(
                      "browse-catalog", Engine.CHROMIUM, CaseStatus.PASSED, 1000, 2400))));
    }

    @Test
    void aNonBlockingRegressionIsReportedButPasses() {
      Evaluation e = QualityRules.evaluate(withDuration(true, false), regressed(), List.of());
      RuleResult duration = rule(e, "duration-regression");
      assertThat(duration.outcome()).isEqualTo(QualityOutcome.FAIL);
      assertThat(duration.blocking()).isFalse();
      assertThat(duration.observed()).isEqualTo(1);
      assertThat(duration.evidence().getFirst().detail())
          .contains("1000 ms -> 2400 ms")
          .contains(LAST_GREEN.toString());
      assertThat(e.outcome()).isEqualTo(QualityOutcome.PASS);
    }

    @Test
    void aBlockingRegressionFails() {
      assertThat(QualityRules.evaluate(withDuration(true, true), regressed(), List.of()).outcome())
          .isEqualTo(QualityOutcome.FAIL);
    }

    @Test
    void withoutALastGreenRunThereIsNothingToMeasure() {
      RunFacts first =
          facts(
              RunStatus.PASSED,
              List.of(target(Engine.CHROMIUM, TargetStatus.PASSED, true)),
              checkout(Engine.CHROMIUM),
              DurationFacts.noLastGreen());
      RuleResult duration =
          rule(
              QualityRules.evaluate(withDuration(true, true), first, List.of()),
              "duration-regression");
      assertThat(duration.outcome()).isEqualTo(QualityOutcome.PASS);
      assertThat(duration.observed()).isNull();
    }

    @Test
    void aDisabledCheckIsNeverBlocking() {
      RuleResult duration =
          rule(
              QualityRules.evaluate(withDuration(false, true), regressed(), List.of()),
              "duration-regression");
      assertThat(duration.outcome()).isEqualTo(QualityOutcome.PASS);
      assertThat(duration.blocking()).isFalse();
      assertThat(duration.observed()).isNull();
      assertThat(duration.threshold()).isNull();
    }
  }

  @Nested
  class Quarantine {

    private RunFacts cartTotalFailed(Engine engine) {
      return facts(
          RunStatus.FAILED,
          List.of(target(engine, TargetStatus.FAILED, true)),
          checkout(engine, "cart-total", CaseStatus.FAILED),
          new DurationFacts(LAST_GREEN, List.of()));
    }

    @Test
    void qua001AQuarantinedFailureIsReportedAndDoesNotCountTowardFail() {
      QuarantineFact q = quarantine("cart-total", null);
      Evaluation e = QualityRules.evaluate(policy(), cartTotalFailed(Engine.CHROMIUM), List.of(q));
      assertThat(e.outcome()).isEqualTo(QualityOutcome.PASS);
      assertThat(rule(e, "critical-cases").observed()).isEqualTo(0);
      assertThat(rule(e, "critical-cases").evidence().getFirst().status())
          .isEqualTo("QUARANTINED_FAIL");
      assertThat(rule(e, "max-failed-cases").observed()).isEqualTo(0);
      RuleResult honored = rule(e, "quarantine-honored");
      assertThat(honored.observed()).isEqualTo(1);
      assertThat(honored.blocking()).isFalse();
      assertThat(honored.evidence().getFirst().status()).isEqualTo("QUARANTINED_FAIL");
      assertThat(honored.evidence().getFirst().detail()).contains(q.id().toString());
    }

    @Test
    void qua002WithoutTheQuarantineTheSameRunFails() {
      assertThat(
              QualityRules.evaluate(policy(), cartTotalFailed(Engine.CHROMIUM), List.of())
                  .outcome())
          .isEqualTo(QualityOutcome.FAIL);
    }

    @Test
    void anEngineQuarantineCoversOnlyThatEngine() {
      List<QuarantineFact> firefoxOnly = List.of(quarantine("cart-total", Engine.FIREFOX));
      assertThat(
              QualityRules.evaluate(policy(), cartTotalFailed(Engine.CHROMIUM), firefoxOnly)
                  .outcome())
          .isEqualTo(QualityOutcome.FAIL);
    }

    @Test
    void aPolicyThatDoesNotHonourQuarantinesCountsEveryFailure() {
      Evaluation e =
          QualityRules.evaluate(
              withHonor(false),
              cartTotalFailed(Engine.CHROMIUM),
              List.of(quarantine("cart-total", null)));
      assertThat(e.outcome()).isEqualTo(QualityOutcome.FAIL);
      assertThat(rule(e, "quarantine-honored").observed()).isEqualTo(0);
    }

    @Test
    void aQuarantinedFlakeIsNotCountedInTheFlakyRate() {
      RunFacts run =
          facts(
              RunStatus.PASSED,
              List.of(target(Engine.CHROMIUM, TargetStatus.PASSED, true)),
              checkout(
                  Engine.CHROMIUM,
                  "add-to-cart",
                  CaseStatus.FLAKY,
                  "apply-coupon",
                  CaseStatus.FLAKY),
              new DurationFacts(LAST_GREEN, List.of()));
      assertThat(QualityRules.evaluate(policy(), run, List.of()).outcome())
          .isEqualTo(QualityOutcome.FAIL);
      Evaluation e = QualityRules.evaluate(policy(), run, List.of(quarantine("add-to-cart", null)));
      assertThat(rule(e, "max-flaky-rate").observed()).isEqualTo(0.1667);
      assertThat(e.outcome()).isEqualTo(QualityOutcome.PASS);
    }
  }

  /**
   * Invariant I9 over generated inputs (fixed seed, as CONFLICT_REGISTER N28 does for fp_v1): a
   * PASS never comes with a required target that is missing or ended without test results, or with
   * a cancelled run.
   */
  @Test
  void passNeverCoexistsWithAMissingOrInconclusiveRequiredTarget() {
    Random random = new Random(20261124L);
    TargetStatus[] terminal = {
      TargetStatus.PASSED,
      TargetStatus.FAILED,
      TargetStatus.TIMED_OUT,
      TargetStatus.INFRA_ERROR,
      TargetStatus.CANCELLED
    };
    RunStatus[] runs = {
      RunStatus.PASSED,
      RunStatus.FAILED,
      RunStatus.TIMED_OUT,
      RunStatus.INFRA_ERROR,
      RunStatus.CANCELLED
    };
    int passes = 0;
    for (int n = 0; n < 5000; n++) {
      List<String> required = new ArrayList<>();
      List<TargetFact> targets = new ArrayList<>();
      List<CaseFact> cases = new ArrayList<>();
      for (Engine engine : Engine.values()) {
        if (random.nextBoolean()) {
          required.add("local:" + engine.wireName());
        }
        int shards = random.nextInt(3);
        for (int s = 0; s < shards; s++) {
          TargetStatus status = terminal[random.nextInt(terminal.length)];
          boolean results =
              status != TargetStatus.INFRA_ERROR
                  && status != TargetStatus.CANCELLED
                  && random.nextInt(4) > 0;
          targets.add(target(engine, s, status, results, null));
          if (results && status != TargetStatus.TIMED_OUT || results && random.nextBoolean()) {
            cases.add(
                result(
                    engine,
                    "browse-catalog",
                    random.nextBoolean() ? CaseStatus.PASSED : CaseStatus.FLAKY));
          }
        }
      }
      if (required.isEmpty()) {
        required.add("local:chromium");
      }
      QualityPolicy policy =
          with(required, random.nextBoolean(), random.nextInt(3), random.nextBoolean(), 1.0);
      RunStatus run = runs[random.nextInt(runs.length)];
      Evaluation e =
          QualityRules.evaluate(
              policy, facts(run, targets, cases, DurationFacts.noLastGreen()), List.of());
      if (e.outcome() != QualityOutcome.PASS) {
        continue;
      }
      passes++;
      assertThat(run).isNotEqualTo(RunStatus.CANCELLED);
      for (String key : required) {
        List<TargetFact> matching =
            targets.stream().filter(t -> t.requiredKey().equals(key)).toList();
        assertThat(matching).as("required %s in case %d", key, n).isNotEmpty();
        assertThat(matching).allMatch(TargetFact::conclusive);
      }
    }
    assertThat(passes).as("the generator must reach PASS sometimes").isGreaterThan(100);
  }
}

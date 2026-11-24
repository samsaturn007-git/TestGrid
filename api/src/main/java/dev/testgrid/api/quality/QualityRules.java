package dev.testgrid.api.quality;

import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.QualityOutcome;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.TargetStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The quality evaluator (PRD-08 FR-2, contracts §2.4 and §12): a pure function from a policy, the
 * facts of one finished run and the quarantines active at that moment to an outcome with one result
 * per rule. No database, no clock: the service collects the facts, so every boundary is a unit
 * test.
 */
public final class QualityRules {

  public static final String REQUIRED_TARGETS = "required-targets";
  public static final String INFRA_ERROR = "infra-error";
  public static final String CRITICAL_CASES = "critical-cases";
  public static final String MAX_FAILED_CASES = "max-failed-cases";
  public static final String MAX_FLAKY_RATE = "max-flaky-rate";
  public static final String DURATION_REGRESSION = "duration-regression";
  public static final String QUARANTINE_HONORED = "quarantine-honored";

  /** Reported instead of FAILED / TIMED_OUT for a failure that an active quarantine excuses. */
  public static final String QUARANTINED_FAIL = "QUARANTINED_FAIL";

  private QualityRules() {}

  /** A target of the run. {@code errorClass} is its newest attempt's, when it has one. */
  public record TargetFact(
      String targetKey,
      Executor executor,
      Engine engine,
      TargetStatus status,
      boolean hasResults,
      ErrorClass errorClass) {

    /**
     * There is test evidence for this target (CONFLICT_REGISTER N37): it ended PASSED or FAILED, or
     * TIMED_OUT with case results. A TIMED_OUT target without results timed out as a whole, which
     * says nothing about the product.
     */
    boolean conclusive() {
      return status == TargetStatus.PASSED
          || status == TargetStatus.FAILED
          || (status == TargetStatus.TIMED_OUT && hasResults);
    }

    /** The policy's name for it (CONFLICT_REGISTER N34): {@code <executor>:<engine>}. */
    String requiredKey() {
      return executor.name().toLowerCase(Locale.ROOT) + ":" + engine.wireName();
    }
  }

  /** A case result of a conclusive target's newest attempt with results. */
  public record CaseFact(
      String targetKey, Engine engine, String caseKey, CaseStatus status, boolean critical) {

    boolean failed() {
      return status == CaseStatus.FAILED || status == CaseStatus.TIMED_OUT;
    }
  }

  /** An active quarantine. {@code engine} null covers every engine. */
  public record QuarantineFact(UUID id, String caseKey, Engine engine, Instant expiresAt) {

    boolean covers(String caseKey, Engine engine) {
      return this.caseKey.equals(caseKey) && (this.engine == null || this.engine == engine);
    }
  }

  /** A case that ran slower than the policy allows, against the last green run. */
  public record Regression(
      String caseKey, Engine engine, CaseStatus headStatus, int baseMs, int headMs) {}

  /** {@code lastGreenRunId} null: there was no PASSED run to compare with. */
  public record DurationFacts(UUID lastGreenRunId, List<Regression> regressions) {

    public static DurationFacts noLastGreen() {
      return new DurationFacts(null, List.of());
    }
  }

  public record RunFacts(
      RunStatus runStatus,
      List<TargetFact> targets,
      List<CaseFact> cases,
      DurationFacts duration) {}

  /**
   * One fact behind a rule. {@code target} is the target key, or the policy's {@code
   * <executor>:<engine>} for a required target the run doesn't have. {@code status} is the target
   * or case status, {@code QUARANTINED_FAIL} for an excused failure, or null for a note. {@code
   * counted} says whether it counts toward {@code observed}.
   */
  @Schema(name = "QualityRuleEvidence")
  public record Evidence(
      @ApiNullable String target,
      @ApiNullable Engine engine,
      @ApiNullable String caseKey,
      @ApiNullable String status,
      boolean counted,
      String detail) {

    static Evidence note(String detail) {
      return new Evidence(null, null, null, null, false, detail);
    }
  }

  /**
   * Contracts §12 rule result. {@code observed} and {@code threshold} are null when not measured.
   */
  @Schema(name = "QualityRuleResult")
  public record RuleResult(
      String ruleId,
      @ApiNullable Number observed,
      @ApiNullable Number threshold,
      QualityOutcome outcome,
      boolean blocking,
      List<Evidence> evidence) {}

  public record Evaluation(QualityOutcome outcome, List<RuleResult> rules) {}

  public static Evaluation evaluate(
      QualityPolicy policy, RunFacts facts, List<QuarantineFact> activeQuarantines) {
    List<QuarantineFact> quarantines =
        policy.quarantine().honor() ? List.copyOf(activeQuarantines) : List.of();
    List<RuleResult> rules =
        List.of(
            requiredTargets(policy, facts),
            infraError(policy, facts),
            criticalCases(policy, facts, quarantines),
            maxFailedCases(policy, facts, quarantines),
            maxFlakyRate(policy, facts, quarantines),
            durationRegression(policy, facts, quarantines),
            quarantineHonored(policy, facts, quarantines));
    return new Evaluation(overall(rules), rules);
  }

  /**
   * Contracts §2.4: test evidence of a failure wins, then anything uncertain; PASS needs every
   * blocking rule to pass. A FAIL with an infra error elsewhere is still FAIL: a confirmed product
   * failure is the more useful signal (the same order as the run aggregate, §2.3).
   */
  static QualityOutcome overall(List<RuleResult> rules) {
    if (rules.stream().anyMatch(r -> r.blocking() && r.outcome() == QualityOutcome.FAIL)) {
      return QualityOutcome.FAIL;
    }
    if (rules.stream().anyMatch(r -> r.blocking() && r.outcome() == QualityOutcome.INDETERMINATE)) {
      return QualityOutcome.INDETERMINATE;
    }
    return QualityOutcome.PASS;
  }

  /** Always blocking: invariant I9, PASS never coexists with a missing or inconclusive target. */
  static RuleResult requiredTargets(QualityPolicy policy, RunFacts facts) {
    List<Evidence> evidence = new ArrayList<>();
    int satisfied = 0;
    for (String required : policy.requiredTargets()) {
      List<TargetFact> matches =
          facts.targets().stream().filter(t -> t.requiredKey().equals(required)).toList();
      if (matches.isEmpty()) {
        evidence.add(
            new Evidence(
                required, null, null, null, false, "the run has no " + required + " target"));
        continue;
      }
      boolean all = true;
      for (TargetFact target : matches) {
        boolean ok = target.conclusive();
        all &= ok;
        evidence.add(
            new Evidence(
                target.targetKey(),
                target.engine(),
                null,
                target.status().name(),
                ok,
                ok
                    ? "ended " + target.status() + " with test results"
                    : "ended " + target.status() + errorSuffix(target) + " without test results"));
      }
      if (all) {
        satisfied++;
      }
    }
    boolean cancelled = facts.runStatus() == RunStatus.CANCELLED;
    if (cancelled) {
      evidence.add(Evidence.note("the run was cancelled"));
    }
    boolean pass = satisfied == policy.requiredTargets().size() && !cancelled;
    return new RuleResult(
        REQUIRED_TARGETS,
        satisfied,
        policy.requiredTargets().size(),
        pass ? QualityOutcome.PASS : QualityOutcome.INDETERMINATE,
        true,
        evidence);
  }

  static RuleResult infraError(QualityPolicy policy, RunFacts facts) {
    List<Evidence> evidence =
        facts.targets().stream()
            .filter(t -> t.status() == TargetStatus.INFRA_ERROR)
            .map(t ->
                new Evidence(
                    t.targetKey(),
                    t.engine(),
                    null,
                    t.status().name(),
                    true,
                    "ended INFRA_ERROR" + errorSuffix(t)))
            .toList();
    return new RuleResult(
        INFRA_ERROR,
        evidence.size(),
        0,
        evidence.isEmpty() ? QualityOutcome.PASS : QualityOutcome.INDETERMINATE,
        policy.failOnInfraError(),
        evidence);
  }

  static RuleResult criticalCases(
      QualityPolicy policy, RunFacts facts, List<QuarantineFact> quarantines) {
    List<Evidence> evidence = new ArrayList<>();
    int counted = 0;
    for (CaseFact c : facts.cases()) {
      if (!c.failed() || !c.critical()) {
        continue;
      }
      Optional<QuarantineFact> q = quarantineOf(c.caseKey(), c.engine(), quarantines);
      counted += q.isEmpty() ? 1 : 0;
      evidence.add(failureEvidence(c, q, "critical case " + c.status()));
    }
    return new RuleResult(
        CRITICAL_CASES,
        counted,
        0,
        counted == 0 ? QualityOutcome.PASS : QualityOutcome.FAIL,
        policy.failOnCriticalCase(),
        evidence);
  }

  /** {@code observed > maxFailedCases} fails; equality passes. */
  static RuleResult maxFailedCases(
      QualityPolicy policy, RunFacts facts, List<QuarantineFact> quarantines) {
    List<Evidence> evidence = new ArrayList<>();
    int counted = 0;
    for (CaseFact c : facts.cases()) {
      if (!c.failed()) {
        continue;
      }
      Optional<QuarantineFact> q = quarantineOf(c.caseKey(), c.engine(), quarantines);
      counted += q.isEmpty() ? 1 : 0;
      evidence.add(failureEvidence(c, q, c.status().name()));
    }
    return new RuleResult(
        MAX_FAILED_CASES,
        counted,
        policy.maxFailedCases(),
        counted > policy.maxFailedCases() ? QualityOutcome.FAIL : QualityOutcome.PASS,
        true,
        evidence);
  }

  /**
   * FLAKY results over executed (non-SKIPPED) results; {@code rate > maxFlakyRate} fails, equality
   * passes. The comparison uses the unrounded quotient: when it equals the threshold exactly (1/5
   * and 0.2), both are the same nearest double, so equality holds. {@code observed} is rounded to
   * four places for display only.
   */
  static RuleResult maxFlakyRate(
      QualityPolicy policy, RunFacts facts, List<QuarantineFact> quarantines) {
    List<Evidence> evidence = new ArrayList<>();
    int executed = 0;
    int flaky = 0;
    for (CaseFact c : facts.cases()) {
      if (c.status() == CaseStatus.SKIPPED) {
        continue;
      }
      executed++;
      if (c.status() != CaseStatus.FLAKY) {
        continue;
      }
      Optional<QuarantineFact> q = quarantineOf(c.caseKey(), c.engine(), quarantines);
      flaky += q.isEmpty() ? 1 : 0;
      evidence.add(
          new Evidence(
              c.targetKey(),
              c.engine(),
              c.caseKey(),
              c.status().name(),
              q.isEmpty(),
              q.map(QualityRules::quarantinedDetail).orElse("passed only on a retry")));
    }
    double rate = executed == 0 ? 0.0 : (double) flaky / executed;
    return new RuleResult(
        MAX_FLAKY_RATE,
        BigDecimal.valueOf(rate).setScale(4, RoundingMode.HALF_UP).doubleValue(),
        policy.maxFlakyRate(),
        rate > policy.maxFlakyRate() ? QualityOutcome.FAIL : QualityOutcome.PASS,
        true,
        evidence);
  }

  static RuleResult durationRegression(
      QualityPolicy policy, RunFacts facts, List<QuarantineFact> quarantines) {
    QualityPolicy.DurationRegression config = policy.durationRegression();
    if (!config.enabled()) {
      return new RuleResult(
          DURATION_REGRESSION,
          null,
          null,
          QualityOutcome.PASS,
          false,
          List.of(Evidence.note("the duration check is off in this policy")));
    }
    DurationFacts duration = facts.duration();
    if (duration.lastGreenRunId() == null) {
      return new RuleResult(
          DURATION_REGRESSION,
          null,
          0,
          QualityOutcome.PASS,
          config.blocking(),
          List.of(
              Evidence.note("no earlier PASSED run of this suite on this branch to compare with")));
    }
    List<Evidence> evidence = new ArrayList<>();
    int counted = 0;
    for (Regression r : duration.regressions()) {
      Optional<QuarantineFact> q = quarantineOf(r.caseKey(), r.engine(), quarantines);
      counted += q.isEmpty() ? 1 : 0;
      String slower =
          r.baseMs() + " ms -> " + r.headMs() + " ms against run " + duration.lastGreenRunId();
      evidence.add(
          new Evidence(
              null,
              r.engine(),
              r.caseKey(),
              r.headStatus().name(),
              q.isEmpty(),
              q.map(x -> slower + "; " + quarantinedDetail(x)).orElse(slower)));
    }
    return new RuleResult(
        DURATION_REGRESSION,
        counted,
        0,
        counted == 0 ? QualityOutcome.PASS : QualityOutcome.FAIL,
        config.blocking(),
        evidence);
  }

  /** Never blocking: it reports what quarantines excused, so they stay visible. */
  static RuleResult quarantineHonored(
      QualityPolicy policy, RunFacts facts, List<QuarantineFact> quarantines) {
    if (!policy.quarantine().honor()) {
      return new RuleResult(
          QUARANTINE_HONORED,
          0,
          null,
          QualityOutcome.PASS,
          false,
          List.of(
              Evidence.note(
                  "this policy does not honour quarantines: quarantined cases count like any other")));
    }
    List<Evidence> evidence = new ArrayList<>();
    for (CaseFact c : facts.cases()) {
      if (c.failed()) {
        quarantineOf(c.caseKey(), c.engine(), quarantines)
            .ifPresent(q -> evidence.add(failureEvidence(c, Optional.of(q), "")));
      }
    }
    return new RuleResult(
        QUARANTINE_HONORED, evidence.size(), null, QualityOutcome.PASS, false, evidence);
  }

  private static Optional<QuarantineFact> quarantineOf(
      String caseKey, Engine engine, List<QuarantineFact> quarantines) {
    return quarantines.stream().filter(q -> q.covers(caseKey, engine)).findFirst();
  }

  private static Evidence failureEvidence(
      CaseFact c, Optional<QuarantineFact> quarantine, String detail) {
    return quarantine
        .map(q ->
            new Evidence(
                c.targetKey(),
                c.engine(),
                c.caseKey(),
                QUARANTINED_FAIL,
                false,
                c.status() + "; " + quarantinedDetail(q)))
        .orElseGet(() ->
            new Evidence(c.targetKey(), c.engine(), c.caseKey(), c.status().name(), true, detail));
  }

  private static String quarantinedDetail(QuarantineFact q) {
    return "quarantined (" + q.id() + ") until " + q.expiresAt() + ", not counted";
  }

  private static String errorSuffix(TargetFact target) {
    return target.errorClass() == null ? "" : " (" + target.errorClass() + ")";
  }
}

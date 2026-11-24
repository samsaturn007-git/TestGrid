package dev.testgrid.api.failure;

import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.FailureCategory;
import dev.testgrid.api.failure.FailureRules.Agreeing;
import dev.testgrid.api.failure.FailureRules.Condition;
import dev.testgrid.api.failure.FailureRules.Rule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PRD-07 FR-2: labels a failure group with the first rule that matches its facts. Pure and
 * deterministic: the same facts always give the same label, confidence and explanation, which is
 * stored with the group so the UI shows why, not just what. O(rules × alternatives).
 */
public final class FailureClassifier {

  /** What is known about one failure group, counted over all its occurrences. */
  public record Facts(
      ErrorClass errorClass,
      int occurrences,
      int attempts,
      int runs,
      int engines,
      int passedOnRetry,
      Set<String> statusesSeen) {

    public Facts {
      statusesSeen = Set.copyOf(statusesSeen);
    }
  }

  /** One rule as it was tried, in order, up to and including the one that matched. */
  public record RuleCheck(
      String ruleId, String name, FailureCategory category, boolean matched, String reason) {}

  /** {@code value = clamp(base + occurrenceBonus − disagreementPenalty, floor, cap)}. */
  public record ConfidenceWork(
      BigDecimal base,
      BigDecimal occurrenceBonus,
      BigDecimal agreement,
      BigDecimal disagreementPenalty,
      BigDecimal floor,
      BigDecimal cap,
      BigDecimal value) {}

  public record Evidence(
      String ruleId,
      String ruleName,
      FailureCategory category,
      String explanation,
      Facts facts,
      List<RuleCheck> rulesTried,
      ConfidenceWork confidence) {}

  public record Classification(
      String ruleId, FailureCategory category, BigDecimal confidence, Evidence evidence) {}

  private final FailureRules rules;

  public FailureClassifier(FailureRules rules) {
    this.rules = rules;
  }

  public Classification classify(Facts facts) {
    if (facts.occurrences() < 1) {
      throw new IllegalArgumentException("a failure group has at least one occurrence");
    }
    List<RuleCheck> tried = new ArrayList<>();
    for (Rule rule : rules.rules()) {
      List<String> misses = new ArrayList<>();
      String hit = null;
      for (Condition alternative : rule.when()) {
        String miss = firstMiss(alternative, facts);
        if (miss == null) {
          hit = describe(alternative, facts);
          break;
        }
        misses.add(miss);
      }
      if (hit == null) {
        tried.add(
            new RuleCheck(
                rule.id(), rule.name(), rule.category(), false, String.join(" or ", misses)));
        continue;
      }
      tried.add(new RuleCheck(rule.id(), rule.name(), rule.category(), true, hit));
      ConfidenceWork confidence = confidence(rule, facts);
      Evidence evidence =
          new Evidence(
              rule.id(),
              rule.name(),
              rule.category(),
              rule.explanation(),
              facts,
              List.copyOf(tried),
              confidence);
      return new Classification(rule.id(), rule.category(), confidence.value(), evidence);
    }
    // FailureRules.validate guarantees a catch-all last rule.
    throw new IllegalStateException("no rule matched, but the last rule must match everything");
  }

  /** {@code null} when the alternative holds, otherwise why it doesn't, for the explanation. */
  private static String firstMiss(Condition c, Facts f) {
    if (c.errorClassIn() != null && !c.errorClassIn().contains(f.errorClass())) {
      return "error class " + f.errorClass() + " is not " + c.errorClassIn();
    }
    if (c.statusSeen() != null && !f.statusesSeen().contains(c.statusSeen())) {
      return "no occurrence is " + c.statusSeen();
    }
    if (c.minPassedOnRetry() != null && f.passedOnRetry() < c.minPassedOnRetry()) {
      return f.passedOnRetry()
          + " of "
          + f.occurrences()
          + " occurrences passed on a retry (needs "
          + c.minPassedOnRetry()
          + ")";
    }
    if (c.minAttempts() != null && f.attempts() < c.minAttempts()) {
      return "seen in " + f.attempts() + " attempt(s) (needs " + c.minAttempts() + ")";
    }
    return null;
  }

  private static String describe(Condition c, Facts f) {
    List<String> parts = new ArrayList<>();
    if (c.errorClassIn() != null) {
      parts.add("error class " + f.errorClass());
    }
    if (c.statusSeen() != null) {
      parts.add("an occurrence is " + c.statusSeen());
    }
    if (c.minPassedOnRetry() != null) {
      parts.add(f.passedOnRetry() + " of " + f.occurrences() + " occurrences passed on a retry");
    }
    if (c.minAttempts() != null) {
      parts.add("seen in " + f.attempts() + " attempts across " + f.runs() + " run(s)");
    }
    return parts.isEmpty() ? "matches every failure" : String.join(", ", parts);
  }

  private ConfidenceWork confidence(Rule rule, Facts f) {
    FailureRules.Confidence c = rules.confidence();
    int agreeing =
        rule.agreeingOrDefault() == Agreeing.passedOnRetry
            ? f.passedOnRetry()
            : f.occurrences() - f.passedOnRetry();
    BigDecimal agreement =
        BigDecimal.valueOf(agreeing)
            .divide(BigDecimal.valueOf(f.occurrences()), 4, RoundingMode.HALF_UP);
    BigDecimal base = BigDecimal.valueOf(rule.baseConfidence());
    BigDecimal bonus =
        BigDecimal.valueOf(c.perExtraOccurrence())
            .multiply(BigDecimal.valueOf(Math.min(f.occurrences() - 1, c.maxExtraOccurrences())));
    BigDecimal penalty =
        BigDecimal.valueOf(c.disagreementPenalty()).multiply(BigDecimal.ONE.subtract(agreement));
    BigDecimal floor = scale(BigDecimal.valueOf(c.floor()));
    BigDecimal cap = scale(BigDecimal.valueOf(c.cap()));
    // Summed from the rounded parts, so the arithmetic the UI shows adds up exactly.
    base = scale(base);
    bonus = scale(bonus);
    penalty = scale(penalty);
    BigDecimal value = base.add(bonus).subtract(penalty).max(floor).min(cap);
    return new ConfidenceWork(base, bonus, scale(agreement), penalty, floor, cap, value);
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}

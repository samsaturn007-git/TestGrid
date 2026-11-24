package dev.testgrid.api.failure;

import dev.testgrid.api.domain.AttemptStatus;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.FailureCategory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * PRD-07 FR-2: the ordered rule table from {@code failure-rules.yaml} (contracts §8.2). Loaded once
 * at startup and checked strictly, so a typo in the file stops the API instead of silently changing
 * how failures are labelled.
 */
public record FailureRules(int version, Confidence confidence, List<Rule> rules) {

  public record Confidence(
      double cap,
      double floor,
      double perExtraOccurrence,
      int maxExtraOccurrences,
      double disagreementPenalty) {}

  /** Which occurrences count as fitting the rule when its confidence is adjusted. */
  public enum Agreeing {
    /** Occurrences that failed on every try (the default). */
    notPassedOnRetry,
    /** Occurrences that passed when Playwright retried them. */
    passedOnRetry
  }

  public record Rule(
      String id,
      String name,
      FailureCategory category,
      double baseConfidence,
      Agreeing agreeing,
      String explanation,
      List<Condition> when) {

    public Agreeing agreeingOrDefault() {
      return agreeing == null ? Agreeing.notPassedOnRetry : agreeing;
    }
  }

  /** One alternative of a rule: every field that is set must hold. No field set means "always". */
  public record Condition(
      List<ErrorClass> errorClassIn,
      String statusSeen,
      Integer minPassedOnRetry,
      Integer minAttempts,
      Boolean caseHealthFlaky) {

    boolean always() {
      return errorClassIn == null
          && statusSeen == null
          && minPassedOnRetry == null
          && minAttempts == null
          && caseHealthFlaky == null;
    }
  }

  /** Error classes that mean the platform failed, not the product (AC-2, risk R7). */
  static final Set<ErrorClass> INFRASTRUCTURE =
      Set.of(
          ErrorClass.BROWSER_LAUNCH,
          ErrorClass.WORKER_LOST,
          ErrorClass.WORKER_SHUTDOWN,
          ErrorClass.LEASE_EXPIRED,
          ErrorClass.NETWORK,
          ErrorClass.ARTIFACT_STORE,
          ErrorClass.CONFIG,
          ErrorClass.CANCELLED);

  private static final Set<String> STATUSES =
      Stream.concat(
              Stream.of(CaseStatus.values()).map(Enum::name),
              Stream.of(AttemptStatus.values()).map(Enum::name))
          .collect(Collectors.toUnmodifiableSet());

  public static FailureRules load(InputStream yaml) {
    Object tree;
    try {
      tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    } catch (YAMLException e) {
      throw new InvalidFailureRulesException(
          "failure-rules.yaml is not valid YAML: " + e.getMessage());
    }
    JsonMapper mapper =
        JsonMapper.builder()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
    FailureRules rules;
    try {
      rules = mapper.convertValue(tree, FailureRules.class);
    } catch (JacksonException e) {
      throw new InvalidFailureRulesException("failure-rules.yaml: " + e.getOriginalMessage());
    }
    if (rules == null) {
      throw new InvalidFailureRulesException("failure-rules.yaml is empty");
    }
    rules.validate();
    return rules;
  }

  void validate() {
    List<String> problems = new ArrayList<>();
    if (version != 1) {
      problems.add("version must be 1");
    }
    if (confidence == null) {
      problems.add("confidence is required");
    } else {
      if (!(confidence.floor() > 0 && confidence.floor() < confidence.cap())) {
        problems.add("confidence.floor must be above 0 and below the cap");
      }
      if (!(confidence.cap() > 0 && confidence.cap() <= 0.95)) {
        problems.add("confidence.cap must be at most 0.95");
      }
      if (confidence.perExtraOccurrence() < 0 || confidence.perExtraOccurrence() > 0.1) {
        problems.add("confidence.perExtraOccurrence must be 0 … 0.1");
      }
      if (confidence.maxExtraOccurrences() < 0 || confidence.maxExtraOccurrences() > 20) {
        problems.add("confidence.maxExtraOccurrences must be 0 … 20");
      }
      if (confidence.disagreementPenalty() < 0 || confidence.disagreementPenalty() > 1) {
        problems.add("confidence.disagreementPenalty must be 0 … 1");
      }
    }
    if (rules == null || rules.isEmpty()) {
      problems.add("rules must not be empty");
    } else {
      Set<String> ids = new HashSet<>();
      for (Rule rule : rules) {
        String at = "rule " + rule.id();
        if (rule.id() == null || !rule.id().matches("^R[0-9]{1,3}$") || !ids.add(rule.id())) {
          problems.add(at + ": id must be a unique R<number>");
        }
        if (rule.name() == null || !rule.name().matches("^[a-z0-9-]{1,64}$")) {
          problems.add(at + ": name must be lowercase words joined by '-'");
        }
        if (rule.category() == null) {
          problems.add(at + ": category is required");
        }
        double cap = confidence == null ? 0.95 : confidence.cap();
        if (!(rule.baseConfidence() > 0 && rule.baseConfidence() <= cap)) {
          problems.add(at + ": baseConfidence must be above 0 and at most the cap");
        }
        if (rule.explanation() == null
            || rule.explanation().isBlank()
            || rule.explanation().length() > 300) {
          problems.add(at + ": explanation must be 1 … 300 characters");
        }
        if (rule.when() == null || rule.when().isEmpty() || rule.when().contains(null)) {
          problems.add(at + ": when needs at least one alternative");
          continue;
        }
        for (Condition c : rule.when()) {
          if (c.errorClassIn() != null
              && (c.errorClassIn().isEmpty() || c.errorClassIn().contains(null))) {
            problems.add(at + ": errorClassIn must list error classes");
          }
          if (c.statusSeen() != null && !STATUSES.contains(c.statusSeen())) {
            problems.add(
                at + ": statusSeen " + c.statusSeen() + " is not a case or attempt status");
          }
          if (c.minPassedOnRetry() != null && c.minPassedOnRetry() < 1) {
            problems.add(at + ": minPassedOnRetry must be at least 1");
          }
          if (c.minAttempts() != null && c.minAttempts() < 1) {
            problems.add(at + ": minAttempts must be at least 1");
          }
          // AC-2 as a property of the table, not only of today's data: a product label needs an
          // assertion, so no infrastructure error class can ever reach it.
          if (rule.category() == FailureCategory.PRODUCT_SUSPECTED
              && (c.errorClassIn() == null
                  || !Set.of(ErrorClass.ASSERTION).containsAll(c.errorClassIn()))) {
            problems.add(
                at + ": a PRODUCT_SUSPECTED alternative must require errorClassIn [ASSERTION]");
          }
        }
      }
      if (rules.getLast().when() == null
          || rules.getLast().when().stream().noneMatch(c -> c != null && c.always())) {
        problems.add("the last rule must match everything (when: [{}])");
      }
    }
    if (!problems.isEmpty()) {
      throw new InvalidFailureRulesException("failure-rules.yaml: " + String.join("; ", problems));
    }
  }

  public static final class InvalidFailureRulesException extends RuntimeException {
    public InvalidFailureRulesException(String message) {
      super(message);
    }
  }
}

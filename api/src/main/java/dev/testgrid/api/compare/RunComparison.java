package dev.testgrid.api.compare;

import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.TargetStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * PRD-07 FR-6 (contracts §8): what changed between two runs of one suite, per {@code (caseKey,
 * engine)}. Pure; {@link RunComparisonService} loads the two sides.
 *
 * <p>O(n) in the number of (case, engine) results: each side is indexed once into a hash map keyed
 * by {@code (caseKey, engine)}, then every key of the union is looked up in both maps in O(1). The
 * final sort for display is O(n log n) over the rows, not part of the comparison.
 */
public final class RunComparison {

  /** Contracts §8, in the order the UI shows them. */
  @Schema(name = "ComparisonCategory")
  public enum Category {
    NEW_FAILURE,
    FIXED,
    STILL_FAILING,
    FLAKY_IN_HEAD,
    ADDED,
    REMOVED,
    NOT_COMPARABLE,
    STILL_PASSING
  }

  public record CaseOutcome(CaseStatus status, int durationMs, UUID fingerprintId) {}

  /**
   * One engine's target in one run. {@code results} is {@code null} when the target has nothing to
   * compare: not finished, INFRA_ERROR or CANCELLED, or no case results.
   */
  public record EngineSide(TargetStatus targetStatus, Map<String, CaseOutcome> results) {}

  /** {@code cases}: the case keys of the run's suite revision. */
  public record Side(Set<String> cases, Map<Engine, EngineSide> engines) {}

  public record Key(String caseKey, Engine engine) {}

  /** Contracts §8: a regression is slower than {@code base × ratio} and {@code base + minDelta}. */
  public record Thresholds(double ratio, int minDeltaMs) {}

  @Schema(name = "ComparisonRow")
  public record Row(
      String caseKey,
      Engine engine,
      Category category,
      @ApiNullable CaseStatus baseStatus,
      @ApiNullable CaseStatus headStatus,
      @ApiNullable Integer baseDurationMs,
      @ApiNullable Integer headDurationMs,
      boolean durationRegression,
      @ApiNullable UUID baseFingerprintId,
      @ApiNullable UUID headFingerprintId,
      boolean fingerprintChanged,
      @ApiNullable String headHealthLabel,
      @ApiNullable String reason) {}

  public record Result(List<Row> rows, Map<Category, Integer> counts) {}

  private RunComparison() {}

  private record Cell(EngineSide target, CaseOutcome outcome) {}

  private static Map<Key, Cell> index(Side side) {
    Map<Key, Cell> index = new HashMap<>();
    side.engines()
        .forEach((engine, target) -> {
          for (String caseKey : side.cases()) {
            CaseOutcome outcome = target.results() == null ? null : target.results().get(caseKey);
            index.put(new Key(caseKey, engine), new Cell(target, outcome));
          }
        });
    return index;
  }

  /** {@code headHealth}: the head side's current case_health label per key, when there is one. */
  public static Result compare(
      Side base, Side head, Map<Key, String> headHealth, Thresholds thresholds) {
    Map<Key, Cell> baseIndex = index(base);
    Map<Key, Cell> headIndex = index(head);
    Set<String> cases = new TreeSet<>(base.cases());
    cases.addAll(head.cases());
    Set<Engine> engines = new TreeSet<>(base.engines().keySet());
    engines.addAll(head.engines().keySet());

    List<Row> rows = new ArrayList<>();
    for (String caseKey : cases) {
      for (Engine engine : engines) {
        Key key = new Key(caseKey, engine);
        Row row =
            row(
                key,
                base,
                head,
                baseIndex.get(key),
                headIndex.get(key),
                headHealth.get(key),
                thresholds);
        if (row != null) {
          rows.add(row);
        }
      }
    }
    rows.sort(
        Comparator.comparing(Row::category).thenComparing(Row::caseKey).thenComparing(Row::engine));
    Map<Category, Integer> counts = new EnumMap<>(Category.class);
    for (Category c : Category.values()) {
      counts.put(c, 0);
    }
    rows.forEach(r -> counts.merge(r.category(), 1, Integer::sum));
    return new Result(List.copyOf(rows), counts);
  }

  private static Row row(
      Key key, Side base, Side head, Cell b, Cell h, String headHealth, Thresholds thresholds) {
    String caseKey = key.caseKey();
    Engine engine = key.engine();
    CaseOutcome bo = b == null ? null : b.outcome();
    CaseOutcome ho = h == null ? null : h.outcome();
    if (!base.cases().contains(caseKey)) {
      // Only where head ran that engine: a key of an engine head never had says nothing.
      return h == null
          ? null
          : new Row(
              caseKey,
              engine,
              Category.ADDED,
              null,
              status(ho),
              null,
              duration(ho),
              false,
              null,
              fingerprint(ho),
              false,
              headHealth,
              "not in the base run's suite revision");
    }
    if (!head.cases().contains(caseKey)) {
      return b == null
          ? null
          : new Row(
              caseKey,
              engine,
              Category.REMOVED,
              status(bo),
              null,
              duration(bo),
              null,
              false,
              fingerprint(bo),
              null,
              false,
              headHealth,
              "not in the head run's suite revision");
    }
    String missing = missing("base", b, bo, engine);
    if (missing == null) {
      missing = missing("head", h, ho, engine);
    }
    if (missing != null) {
      return new Row(
          caseKey,
          engine,
          Category.NOT_COMPARABLE,
          status(bo),
          status(ho),
          duration(bo),
          duration(ho),
          false,
          fingerprint(bo),
          fingerprint(ho),
          false,
          headHealth,
          missing);
    }
    boolean regression =
        (double) ho.durationMs() > bo.durationMs() * thresholds.ratio()
            && (long) ho.durationMs() > (long) bo.durationMs() + thresholds.minDeltaMs();
    boolean basePassed = passed(bo.status());
    boolean headPassed = passed(ho.status());
    Category category;
    String reason = null;
    boolean fingerprintChanged = false;
    if (ho.status() == CaseStatus.FLAKY) {
      category = Category.FLAKY_IN_HEAD;
      reason = "passed only on a retry in head";
    } else if ("FLAKY".equals(headHealth)) {
      category = Category.FLAKY_IN_HEAD;
      reason = "its history on " + engine.wireName() + " is labelled FLAKY";
    } else if (basePassed && !headPassed) {
      category = Category.NEW_FAILURE;
    } else if (!basePassed && headPassed) {
      category = Category.FIXED;
    } else if (!basePassed) {
      category = Category.STILL_FAILING;
      fingerprintChanged =
          bo.fingerprintId() != null
              && ho.fingerprintId() != null
              && !bo.fingerprintId().equals(ho.fingerprintId());
      if (fingerprintChanged) {
        reason = "the failure signature changed";
      }
    } else {
      category = Category.STILL_PASSING;
    }
    return new Row(
        caseKey,
        engine,
        category,
        bo.status(),
        ho.status(),
        bo.durationMs(),
        ho.durationMs(),
        regression,
        bo.fingerprintId(),
        ho.fingerprintId(),
        fingerprintChanged,
        headHealth,
        reason);
  }

  /** Why one side has nothing to compare, or {@code null} when it has a result that ran. */
  private static String missing(String side, Cell cell, CaseOutcome outcome, Engine engine) {
    if (cell == null) {
      return "no " + engine.wireName() + " target in the " + side + " run";
    }
    if (cell.target().results() == null) {
      return "the " + side + " target is " + cell.target().targetStatus() + " with no case results";
    }
    if (outcome == null) {
      return "the " + side + " run has no result for this case";
    }
    if (outcome.status() == CaseStatus.SKIPPED) {
      return "skipped in the " + side + " run";
    }
    return null;
  }

  static boolean passed(CaseStatus status) {
    return status == CaseStatus.PASSED || status == CaseStatus.FLAKY;
  }

  private static CaseStatus status(CaseOutcome o) {
    return o == null ? null : o.status();
  }

  private static Integer duration(CaseOutcome o) {
    return o == null ? null : o.durationMs();
  }

  private static UUID fingerprint(CaseOutcome o) {
    return o == null ? null : o.fingerprintId();
  }
}

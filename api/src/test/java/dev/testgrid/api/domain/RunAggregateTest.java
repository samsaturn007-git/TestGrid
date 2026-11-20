package dev.testgrid.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.domain.RunAggregate.TargetState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** AC-5: the contracts §2.3 truth table for every combination of 1-3 target states. */
class RunAggregateTest {

  /**
   * Every state a single target can be in. Statuses reachable only through a claim always have an
   * attempt; QUEUED, INFRA_ERROR and CANCELLED appear both with and without one.
   */
  private static final List<TargetState> STATES =
      List.of(
          new TargetState(TargetStatus.QUEUED, 0),
          new TargetState(TargetStatus.QUEUED, 1),
          new TargetState(TargetStatus.CLAIMED, 1),
          new TargetState(TargetStatus.RUNNING, 1),
          new TargetState(TargetStatus.PASSED, 1),
          new TargetState(TargetStatus.FAILED, 1),
          new TargetState(TargetStatus.TIMED_OUT, 1),
          new TargetState(TargetStatus.INFRA_ERROR, 0),
          new TargetState(TargetStatus.INFRA_ERROR, 1),
          new TargetState(TargetStatus.CANCELLED, 0),
          new TargetState(TargetStatus.CANCELLED, 1));

  @Test
  void everyCombinationOfOneToThreeTargets() {
    List<List<TargetState>> combinations = new ArrayList<>();
    for (TargetState a : STATES) {
      combinations.add(List.of(a));
      for (TargetState b : STATES) {
        combinations.add(List.of(a, b));
        for (TargetState c : STATES) {
          combinations.add(List.of(a, b, c));
        }
      }
    }
    assertThat(combinations).hasSize(11 + 11 * 11 + 11 * 11 * 11);

    for (List<TargetState> targets : combinations) {
      assertThat(RunAggregate.of(targets)).as(targets.toString()).isEqualTo(expected(targets));
    }
  }

  /** Every bare status combination (8 + 64 + 512) is also covered, with natural attempt counts. */
  @Test
  void everyStatusCombinationIsReachable() {
    int covered = 0;
    for (TargetStatus a : TargetStatus.values()) {
      for (TargetStatus b : TargetStatus.values()) {
        for (TargetStatus c : TargetStatus.values()) {
          List<TargetState> three = List.of(natural(a), natural(b), natural(c));
          assertThat(RunAggregate.of(three)).isEqualTo(expected(three));
          covered++;
        }
        List<TargetState> two = List.of(natural(a), natural(b));
        assertThat(RunAggregate.of(two)).isEqualTo(expected(two));
        covered++;
      }
      assertThat(RunAggregate.of(List.of(natural(a)))).isEqualTo(expected(List.of(natural(a))));
      covered++;
    }
    assertThat(covered).isEqualTo(8 + 64 + 512);
  }

  /** The table as a person would read it; each row is "target states → run status". */
  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "QUEUED:0,                          QUEUED",
    "QUEUED:0 QUEUED:0 QUEUED:0,        QUEUED",
    "CLAIMED:1,                         RUNNING",
    "QUEUED:0 RUNNING:1,                RUNNING",
    "QUEUED:1,                          RUNNING",
    "PASSED:1 QUEUED:0,                 RUNNING",
    "CANCELLED:0 QUEUED:0,              QUEUED",
    "INFRA_ERROR:0 QUEUED:0,            QUEUED",
    "PASSED:1,                          PASSED",
    "PASSED:1 PASSED:1 PASSED:1,        PASSED",
    "PASSED:1 FAILED:1,                 FAILED",
    "FAILED:1 TIMED_OUT:1 INFRA_ERROR:1, FAILED",
    "PASSED:1 TIMED_OUT:1,              TIMED_OUT",
    "TIMED_OUT:1 INFRA_ERROR:1,         TIMED_OUT",
    "PASSED:1 INFRA_ERROR:0,            INFRA_ERROR",
    "INFRA_ERROR:1 CANCELLED:0,         INFRA_ERROR",
    "PASSED:1 CANCELLED:0,              CANCELLED",
    "CANCELLED:0 CANCELLED:0,           CANCELLED",
  })
  void readableExamples(String targets, RunStatus expected) {
    List<TargetState> states =
        Arrays.stream(targets.trim().split("\\s+"))
            .map(s -> {
              String[] parts = s.split(":");
              return new TargetState(TargetStatus.valueOf(parts[0]), Integer.parseInt(parts[1]));
            })
            .toList();
    assertThat(RunAggregate.of(states)).isEqualTo(expected);
  }

  @Test
  void aRunWithoutTargetsIsRejected() {
    assertThatThrownBy(() -> RunAggregate.of(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static TargetState natural(TargetStatus status) {
    return new TargetState(status, status == TargetStatus.QUEUED ? 0 : 1);
  }

  /**
   * Independent restatement of contracts §2.3, written as nested conditions rather than a priority
   * list, so a mistake in one is unlikely to be repeated in the other.
   */
  private static RunStatus expected(List<TargetState> targets) {
    boolean anyActive =
        targets.stream()
            .anyMatch(t ->
                t.status() == TargetStatus.QUEUED
                    || t.status() == TargetStatus.CLAIMED
                    || t.status() == TargetStatus.RUNNING);
    if (anyActive) {
      boolean claimed =
          targets.stream()
              .anyMatch(t ->
                  t.attemptCount() > 0
                      || (t.status() != TargetStatus.QUEUED
                          && t.status() != TargetStatus.INFRA_ERROR
                          && t.status() != TargetStatus.CANCELLED));
      return claimed ? RunStatus.RUNNING : RunStatus.QUEUED;
    }
    if (has(targets, TargetStatus.FAILED)) {
      return RunStatus.FAILED;
    }
    if (has(targets, TargetStatus.TIMED_OUT)) {
      return RunStatus.TIMED_OUT;
    }
    if (has(targets, TargetStatus.INFRA_ERROR)) {
      return RunStatus.INFRA_ERROR;
    }
    if (has(targets, TargetStatus.CANCELLED)) {
      return RunStatus.CANCELLED;
    }
    return RunStatus.PASSED;
  }

  private static boolean has(List<TargetState> targets, TargetStatus status) {
    return targets.stream().anyMatch(t -> t.status() == status);
  }
}

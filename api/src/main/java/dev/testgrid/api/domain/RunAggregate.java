package dev.testgrid.api.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Contracts §2.3: a run's status as a pure function of its targets. Writers that change targets
 * (PRD-03 onward) must store this value in {@code runs.status} in the same transaction.
 */
public final class RunAggregate {

  /** What the aggregate needs to know about one target. */
  public record TargetState(TargetStatus status, int attemptCount) {

    /**
     * A target has been claimed if it has an attempt, or its status can only be reached through a
     * claim. QUEUED (requeued after a lease expiry), INFRA_ERROR and CANCELLED can be reached
     * either way, so for those the attempt count decides.
     */
    boolean everClaimed() {
      return attemptCount > 0 || CLAIM_ONLY.contains(status);
    }
  }

  static final Set<TargetStatus> TERMINAL =
      EnumSet.of(
          TargetStatus.PASSED,
          TargetStatus.FAILED,
          TargetStatus.TIMED_OUT,
          TargetStatus.INFRA_ERROR,
          TargetStatus.CANCELLED);

  private static final Set<TargetStatus> CLAIM_ONLY =
      EnumSet.of(
          TargetStatus.CLAIMED,
          TargetStatus.RUNNING,
          TargetStatus.PASSED,
          TargetStatus.FAILED,
          TargetStatus.TIMED_OUT);

  // First match wins once every target is terminal: a confirmed failure is the most useful signal,
  // uncertain outcomes come next, and PASSED needs every target to pass.
  private static final List<TargetStatus> PRIORITY =
      List.of(
          TargetStatus.FAILED,
          TargetStatus.TIMED_OUT,
          TargetStatus.INFRA_ERROR,
          TargetStatus.CANCELLED,
          TargetStatus.PASSED);

  private RunAggregate() {}

  /** O(n) in the number of targets. */
  public static RunStatus of(List<TargetState> targets) {
    if (targets.isEmpty()) {
      throw new IllegalArgumentException("a run always has at least one target");
    }
    boolean allTerminal = targets.stream().allMatch(t -> TERMINAL.contains(t.status()));
    if (!allTerminal) {
      return targets.stream().anyMatch(TargetState::everClaimed)
          ? RunStatus.RUNNING
          : RunStatus.QUEUED;
    }
    for (TargetStatus candidate : PRIORITY) {
      if (targets.stream().anyMatch(t -> t.status() == candidate)) {
        return RunStatus.valueOf(candidate.name());
      }
    }
    throw new IllegalStateException("unreachable: every terminal status is in PRIORITY");
  }
}

package dev.testgrid.api.domain;

import java.util.List;

/**
 * Contracts §2.2: a target's terminal status computed from its validated case results. The worker's
 * own verdict is never used when results exist, so a worker can't report a false pass.
 */
public final class TargetOutcome {

  /** {@code errorClass} is null unless the outcome needs one (zero cases executed: CONFIG). */
  public record Outcome(TargetStatus status, ErrorClass errorClass) {}

  private TargetOutcome() {}

  /** O(n) in the number of cases. */
  public static Outcome of(List<CaseStatus> cases) {
    if (cases.stream().allMatch(c -> c == CaseStatus.SKIPPED)) {
      return new Outcome(TargetStatus.INFRA_ERROR, ErrorClass.CONFIG);
    }
    if (cases.contains(CaseStatus.FAILED)) {
      return new Outcome(TargetStatus.FAILED, null);
    }
    if (cases.contains(CaseStatus.TIMED_OUT)) {
      return new Outcome(TargetStatus.TIMED_OUT, null);
    }
    return new Outcome(TargetStatus.PASSED, null);
  }
}

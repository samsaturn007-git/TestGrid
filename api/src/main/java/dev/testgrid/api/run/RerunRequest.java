package dev.testgrid.api.run;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /api/v1/runs/{runId}/rerun} (PRD-05 FR-6). The new run copies everything else
 * from the run it reruns, so the client can only choose which targets and the key.
 */
public record RerunRequest(
    @NotNull Scope scope,
    @NotNull
        @Pattern(regexp = "^[A-Za-z0-9._:-]{1,200}$", message = CreateRunRequest.MSG_IDEMPOTENCY)
        String idempotencyKey) {

  /** {@code FAILED_CASES} arrives with PRD-11's sharding; until then it is not a valid value. */
  public enum Scope {
    /** Targets that ended FAILED, TIMED_OUT or INFRA_ERROR. Not PASSED, not CANCELLED. */
    FAILED_TARGETS,
    /** Every target of the run. */
    ALL
  }
}

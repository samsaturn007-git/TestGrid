package dev.testgrid.api.internal;

import dev.testgrid.api.domain.AttemptStatus;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.FixtureMode;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.worker.WorkerState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the worker API (contracts §7). Bean Validation covers shape (400); whether a completion
 * fits its claim (case keys, durations, statuses) is checked by {@link AttemptService} and answered
 * with {@code 422 invalid-completion}.
 */
public final class WorkerApi {

  static final String WORKER_ID = "^[A-Za-z0-9._-]{1,64}$";
  // base64url of 32 random bytes, no padding.
  static final String LEASE_TOKEN = "^[A-Za-z0-9_-]{43}$";
  static final String COMPLETION_KEY = "^[A-Za-z0-9._:-]{1,200}$";
  static final String CASE_KEY = "^[a-z0-9]+(-[a-z0-9]+)*$";

  private WorkerApi() {}

  /**
   * PRD-04 FR-5: the worker claims the target named in an AMQP message. {@code dispatchGeneration}
   * is the fencing token from the message; {@code messageId} is recorded for the audit trail.
   */
  public record ClaimRequest(
      @NotNull @Pattern(regexp = WORKER_ID) String workerId,
      @NotNull UUID messageId,
      @NotNull @Min(1) Integer dispatchGeneration) {}

  static final String WORKER_VERSION = "^[A-Za-z0-9._+-]{1,64}$";

  /** PRD-04 FR-7: {@code name} is what this worker's claims record as {@code workerId}. */
  public record RegisterRequest(
      @NotNull @Pattern(regexp = WORKER_ID) String name,
      @NotNull @Pattern(regexp = WORKER_VERSION) String version,
      @NotNull Executor executor,
      @NotNull @Size(min = 1, max = 3) List<@NotNull Engine> engines,
      @NotNull @Min(1) @Max(16) Integer slots) {}

  public record RegisterResponse(
      UUID id, String name, WorkerState state, int heartbeatIntervalSeconds) {}

  public record WorkerStateResponse(UUID id, WorkerState state) {}

  public record Suite(String suiteKey, String revision) {}

  public record Fixture(FixtureMode mode, int seed) {}

  public record ClaimResponse(
      UUID attemptId,
      int attemptNo,
      UUID runId,
      UUID targetId,
      String leaseToken,
      int leaseTtlSeconds,
      int heartbeatIntervalSeconds,
      Suite suite,
      Engine engine,
      List<String> caseKeys,
      Fixture fixture,
      int timeoutMs) {

    /** The token is a credential: keep it out of any accidental log or toString. */
    @Override
    public String toString() {
      return "ClaimResponse[attemptId=" + attemptId + ", attemptNo=" + attemptNo + "]";
    }
  }

  public record LeaseRequest(@NotNull @Pattern(regexp = LEASE_TOKEN) String leaseToken) {

    @Override
    public String toString() {
      return "LeaseRequest[<redacted>]";
    }
  }

  public record StartResponse(UUID attemptId, AttemptStatus status, Instant leasedUntil) {}

  public record HeartbeatResponse(Instant leasedUntil, boolean cancelRequested) {}

  public enum CompletionStatus {
    COMPLETED_WITH_RESULTS,
    INFRA_ERROR,
    TIMED_OUT,
    CANCELLED
  }

  public record CaseResult(
      @NotNull @Size(max = 64) @Pattern(regexp = CASE_KEY) String caseKey,
      @NotNull CaseStatus status,
      @NotNull Integer durationMs,
      @NotNull Integer retryCount,
      ErrorClass errorClass,
      @Size(max = 2000) String errorMessage,
      // PRD-06 FR-7: optional; ms after the attempt's first case started (Playwright's clock).
      @Min(0) Integer startOffsetMs) {}

  public record WorkerError(@NotNull ErrorClass errorClass, @Size(max = 2000) String message) {}

  public record CompletionRequest(
      @NotNull @Pattern(regexp = LEASE_TOKEN) String leaseToken,
      @NotNull @Pattern(regexp = COMPLETION_KEY) String completionKey,
      @NotNull CompletionStatus status,
      @NotNull @Size(max = 500) List<@NotNull @Valid CaseResult> caseResults,
      @Valid WorkerError workerError) {

    @Override
    public String toString() {
      return "CompletionRequest[completionKey=" + completionKey + ", status=" + status + "]";
    }
  }

  public record CompletionResponse(
      UUID attemptId,
      AttemptStatus attemptStatus,
      ErrorClass errorClass,
      UUID targetId,
      TargetStatus targetStatus,
      UUID runId,
      RunStatus runStatus) {}
}

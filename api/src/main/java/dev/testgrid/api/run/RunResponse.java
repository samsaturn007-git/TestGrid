package dev.testgrid.api.run;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.domain.Trigger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A run as the public API shows it. Never a database row type (PRD-02 risk: no leaked entities).
 * {@code status} is computed from the targets with {@link dev.testgrid.api.domain.RunAggregate}.
 */
public record RunResponse(
    UUID id,
    UUID projectId,
    UUID suiteId,
    String suiteKey,
    String suiteRevision,
    RunStatus status,
    Trigger trigger,
    String idempotencyKey,
    CreateRunRequest.Fixture fixture,
    CreateRunRequest.Sharding sharding,
    CreateRunRequest.Source source,
    Instant queuedAt,
    Instant startedAt,
    Instant finishedAt,
    Instant cancelRequestedAt,
    List<Target> targets,
    Links links) {

  public record Target(
      UUID id,
      String targetKey,
      Executor executor,
      Engine engine,
      TargetStatus status,
      int attemptCount,
      int maxAttempts,
      Instant queuedAt,
      Instant finishedAt) {}

  /** {@code suite} is null in list rows, which don't load it. */
  public record Links(String self, String suite) {}

  public static String selfLink(UUID runId) {
    return "/api/v1/runs/" + runId;
  }

  public static String suiteLink(UUID suiteId) {
    return "/api/v1/suites/" + suiteId;
  }
}

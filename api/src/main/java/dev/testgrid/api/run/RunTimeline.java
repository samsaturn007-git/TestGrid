package dev.testgrid.api.run;

import dev.testgrid.api.artifact.ArtifactView;
import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.TargetStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /runs/{runId}/timeline} (PRD-06 FR-7): the run's events grouped by target and attempt,
 * with each attempt's heartbeats, the gaps between them longer than {@code gapThresholdMs}, its
 * case results and artifacts. Attempts are oldest first, the order a timeline is drawn in.
 */
public record RunTimeline(
    UUID runId,
    RunStatus status,
    Instant queuedAt,
    @ApiNullable Instant finishedAt,
    @Schema(description = "The database clock when this was read; the end of unfinished spans")
        Instant serverNow,
    int heartbeatIntervalSeconds,
    @Schema(description = "2 × the heartbeat interval: a longer silence is a gap")
        long gapThresholdMs,
    @Schema(description = "Events of the run itself (no target)") List<TimelineEvent> runEvents,
    List<Target> targets) {

  @Schema(name = "TimelineTarget")
  public record Target(
      UUID id,
      String targetKey,
      Engine engine,
      TargetStatus status,
      @Schema(description = "Target events outside any attempt (dispatch, retry scheduling)")
          List<TimelineEvent> events,
      List<Attempt> attempts) {}

  @Schema(name = "TimelineAttempt")
  public record Attempt(
      AttemptSummary attempt,
      @Schema(description = "Every accepted heartbeat, oldest first") List<Instant> heartbeats,
      List<Gap> gaps,
      List<RunResults.CaseResult> caseResults,
      List<ArtifactView> artifacts,
      List<TimelineEvent> events) {}

  /** No sign of life from {@code from} to {@code to}: claim, start, heartbeat or the end. */
  @Schema(name = "HeartbeatGap")
  public record Gap(Instant from, Instant to, long durationMs) {}

  @Schema(name = "TimelineEvent")
  public record TimelineEvent(
      long eventId, String type, Instant occurredAt, Map<String, Object> data) {}
}

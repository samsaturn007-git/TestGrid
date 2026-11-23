package dev.testgrid.api.run;

import dev.testgrid.api.artifact.ArtifactView;
import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.TargetStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** {@code GET /runs/{runId}/results} (PRD-03 FR-9): every attempt per target, newest first. */
public record RunResults(UUID runId, RunStatus status, List<Target> targets) {

  @Schema(name = "RunResultsTarget")
  public record Target(
      UUID id, String targetKey, Engine engine, TargetStatus status, List<Attempt> attempts) {}

  @Schema(name = "AttemptResults")
  public record Attempt(
      AttemptSummary attempt,
      @ApiNullable String errorDetail,
      List<CaseResult> caseResults,
      // PRD-06: what was kept for this attempt (screenshots and traces per case, the log tail).
      List<ArtifactView> artifacts) {}

  @Schema(name = "CaseResult")
  public record CaseResult(
      String caseKey,
      CaseStatus status,
      int durationMs,
      int retryCount,
      @ApiNullable ErrorClass errorClass,
      @ApiNullable String errorMessage,
      // PRD-06 FR-7: ms after the attempt's first case started; null if the worker didn't say.
      @ApiNullable Integer startOffsetMs) {}
}

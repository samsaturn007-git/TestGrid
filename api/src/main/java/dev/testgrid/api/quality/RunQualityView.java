package dev.testgrid.api.quality;

import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.QualityOutcome;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.quality.QualityRules.RuleResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/runs/{runId}/quality}. Before the run finishes there is no evaluation: {@code
 * outcome}, {@code evaluationId}, {@code policy} and {@code evaluatedAt} are null and {@code rules}
 * is empty.
 */
public record RunQualityView(
    UUID runId,
    RunStatus runStatus,
    @ApiNullable UUID evaluationId,
    @ApiNullable QualityOutcome outcome,
    @ApiNullable PolicyRef policy,
    @ApiNullable Instant evaluatedAt,
    List<RuleResult> rules) {

  @Schema(name = "QualityPolicyRef")
  public record PolicyRef(UUID id, int version) {}
}

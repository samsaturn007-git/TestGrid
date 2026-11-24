package dev.testgrid.api.compare;

import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.compare.RunComparison.Category;
import dev.testgrid.api.compare.RunComparison.Row;
import dev.testgrid.api.domain.FixtureMode;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.Trigger;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code GET /api/v1/runs/compare} (contracts §8, PRD-07 FR-6). */
public record RunComparisonView(
    RunRef base,
    RunRef head,
    boolean baseIsLastGreen,
    boolean sameRevision,
    ComparisonThresholds thresholds,
    List<CategoryCount> counts,
    int durationRegressions,
    List<Row> rows) {

  @Schema(name = "ComparisonRun")
  public record RunRef(
      UUID id,
      RunStatus status,
      String suiteKey,
      String revision,
      @ApiNullable String branch,
      @ApiNullable String commitSha,
      @ApiNullable FixtureMode fixtureMode,
      @ApiNullable Integer fixtureSeed,
      Trigger trigger,
      Instant queuedAt,
      @ApiNullable Instant finishedAt) {}

  public record ComparisonThresholds(double regressionRatio, int regressionMinDeltaMs) {}

  /** Every category, in display order, zeros included. */
  @Schema(name = "ComparisonCount")
  public record CategoryCount(Category category, int count) {}
}

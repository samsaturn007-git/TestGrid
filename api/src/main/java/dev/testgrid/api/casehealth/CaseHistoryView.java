package dev.testgrid.api.casehealth;

import dev.testgrid.api.casehealth.CaseHealthWindow.Label;
import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.Trigger;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code GET /api/v1/cases/{caseKey}/history} (contracts §4, PRD-07 FR-5). */
public record CaseHistoryView(
    String caseKey, String suiteKey, String title, List<EngineHistory> engines) {

  /** One engine: its health label (null before its first result) and its newest results. */
  @Schema(name = "CaseEngineHistory")
  public record EngineHistory(Engine engine, @ApiNullable Health health, List<Result> results) {}

  /**
   * The case_health row. {@code recentOutcomes} is the window, oldest first: P passed, K passed
   * only on a retry, F failed, T timed out.
   */
  @Schema(name = "CaseHealth")
  public record Health(
      Label label,
      String reason,
      int windowSize,
      int observed,
      int passCount,
      int failCount,
      int flakyCount,
      int flipCount,
      BigDecimal flipRate,
      String recentOutcomes,
      Instant computedAt) {}

  /** One case result, newest first. */
  @Schema(name = "CaseHistoryResult")
  public record Result(
      UUID runId,
      RunStatus runStatus,
      Trigger trigger,
      @ApiNullable String branch,
      @ApiNullable String commitSha,
      UUID targetId,
      UUID attemptId,
      int attemptNo,
      CaseStatus status,
      int durationMs,
      int retryCount,
      @ApiNullable ErrorClass errorClass,
      @ApiNullable UUID fingerprintId,
      Instant finishedAt) {}
}

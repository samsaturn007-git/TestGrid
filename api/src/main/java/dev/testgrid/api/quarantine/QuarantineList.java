package dev.testgrid.api.quarantine;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.quality.QualityPolicy.AutoMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/v1/projects/{projectId}/quarantines}. {@code serverNow} is the database clock, so
 * the dashboard's expiry countdowns don't depend on the browser's. {@code suggestions} are cases
 * labelled FLAKY with no active quarantine, filled only when the policy's autoMode is SUGGEST.
 */
public record QuarantineList(
    List<QuarantineView> items,
    int activeCount,
    int maxActive,
    AutoMode autoMode,
    Instant serverNow,
    List<Suggestion> suggestions) {

  @Schema(name = "QuarantineSuggestion")
  public record Suggestion(
      String suiteKey,
      String caseKey,
      Engine engine,
      int observed,
      int flakyCount,
      double flipRate,
      String reason) {}
}

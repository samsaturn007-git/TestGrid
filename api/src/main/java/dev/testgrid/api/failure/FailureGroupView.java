package dev.testgrid.api.failure;

import dev.testgrid.api.artifact.ArtifactView;
import dev.testgrid.api.audit.AuditRepository.AuditEntry;
import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.FailureCategory;
import dev.testgrid.api.failure.FailureClassifier.Evidence;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/failure-groups/{id}} (contracts §4, PRD-07 FR-2/FR-3/FR-7). {@code category}
 * is what the UI shows: the override when there is one, otherwise the rule's label. The rule's own
 * label, its evidence and the override are always returned beside it.
 */
public record FailureGroupView(
    UUID id,
    String algoVersion,
    String fingerprint,
    ErrorClass errorClass,
    FailureCategory category,
    FailureCategory ruleCategory,
    String ruleId,
    BigDecimal confidence,
    String normalisedMessage,
    int occurrenceCount,
    Instant firstSeenAt,
    Instant lastSeenAt,
    @ApiNullable Override override,
    Evidence evidence,
    List<Occurrence> occurrences,
    List<AuditEntry> audit) {

  @Schema(name = "FailureOverride")
  public record Override(FailureCategory category, String reason, String by, Instant at) {}

  /**
   * One failure in the group: a case result, or a whole attempt (N25) with {@code caseKey: null}.
   * {@code status} is the case status, or the attempt status for an attempt-level failure.
   */
  @Schema(name = "FailureOccurrence")
  public record Occurrence(
      UUID runId,
      UUID targetId,
      UUID attemptId,
      int attemptNo,
      Engine engine,
      String suiteKey,
      @ApiNullable String caseKey,
      String status,
      @ApiNullable String errorMessage,
      Instant occurredAt,
      // PRD-07 FR-7: a case's screenshot and trace plus the attempt's log; the log only for an
      // attempt-level failure. Bytes only through POST /artifacts/{id}/links.
      List<ArtifactView> artifacts) {}
}

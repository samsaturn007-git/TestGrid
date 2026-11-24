package dev.testgrid.api.failure;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.FailureCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/failure-groups} (PRD-07 FR-7). {@code category} is what to show
 * (the override when there is one); {@code ruleId} and {@code confidence} say where it came from.
 */
public record FailureGroupSummary(
    UUID id,
    String algoVersion,
    String fingerprint,
    ErrorClass errorClass,
    FailureCategory category,
    FailureCategory ruleCategory,
    String ruleId,
    BigDecimal confidence,
    boolean overridden,
    String normalisedMessage,
    int occurrenceCount,
    int runCount,
    List<Engine> engines,
    List<String> caseKeys,
    boolean wholeTarget,
    Instant firstSeenAt,
    Instant lastSeenAt,
    Links links) {

  @Schema(name = "FailureGroupLinks")
  public record Links(String self) {}
}

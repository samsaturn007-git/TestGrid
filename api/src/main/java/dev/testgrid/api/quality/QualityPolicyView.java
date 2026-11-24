package dev.testgrid.api.quality;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code GET /api/v1/projects/{projectId}/quality-policy}: one version plus the history. */
public record QualityPolicyView(
    UUID id,
    UUID projectId,
    int version,
    boolean active,
    Instant createdAt,
    QualityPolicy policy,
    List<VersionSummary> versions) {

  /** Newest first. */
  @Schema(name = "QualityPolicyVersion")
  public record VersionSummary(UUID id, int version, boolean active, Instant createdAt) {}
}

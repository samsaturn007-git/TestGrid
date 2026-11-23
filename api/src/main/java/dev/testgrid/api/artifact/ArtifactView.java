package dev.testgrid.api.artifact;

import dev.testgrid.api.common.ApiNullable;
import java.time.Instant;
import java.util.UUID;

/** An artifact as the API shows it. The storage key stays inside the server. */
public record ArtifactView(
    UUID id,
    UUID attemptId,
    @ApiNullable String caseKey,
    ArtifactKind kind,
    String mime,
    long byteSize,
    String sha256,
    ArtifactState state,
    Instant expiresAt,
    Instant createdAt) {

  /**
   * What a reader should see: past {@code expires_at} is expired even before the purge job runs.
   */
  public ArtifactView effective(boolean pastExpiry) {
    if (state != ArtifactState.AVAILABLE || !pastExpiry) {
      return this;
    }
    return new ArtifactView(
        id,
        attemptId,
        caseKey,
        kind,
        mime,
        byteSize,
        sha256,
        ArtifactState.EXPIRED,
        expiresAt,
        createdAt);
  }
}

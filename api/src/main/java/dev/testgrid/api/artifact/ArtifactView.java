package dev.testgrid.api.artifact;

import java.time.Instant;
import java.util.UUID;

/** An artifact as the API shows it. The storage key stays inside the server. */
public record ArtifactView(
    UUID id,
    UUID attemptId,
    String caseKey,
    ArtifactKind kind,
    String mime,
    long byteSize,
    String sha256,
    ArtifactState state,
    Instant expiresAt,
    Instant createdAt) {}

package dev.testgrid.api.artifact;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PRD-06 artifact storage and retrieval (ADR-005).
 *
 * <ul>
 *   <li>{@code root}: the local store's directory, an operator setting ({@code
 *       TESTGRID_ARTIFACT_ROOT}), never anything a client or worker sends.
 *   <li>{@code retention}: how long an artifact stays readable; {@code expires_at} is computed from
 *       the database clock (ARCHITECTURE §6: 7 days).
 *   <li>{@code linkKey}: the HMAC key for signed content links ({@code TESTGRID_ARTIFACT_LINK_KEY},
 *       at least 32 characters). Unset, the API makes a random one at start, so links don't survive
 *       a restart.
 *   <li>{@code linkTtlSeconds}: how long a signed link works, at most 300 (FR-4).
 * </ul>
 */
@ConfigurationProperties("testgrid.artifacts")
public record ArtifactProperties(
    Path root,
    @DefaultValue("7d") Duration retention,
    String linkKey,
    @DefaultValue("300") int linkTtlSeconds) {

  static final int MIN_LINK_KEY_LENGTH = 32;
  static final int MAX_LINK_TTL_SECONDS = 300;

  public ArtifactProperties {
    if (root == null) {
      throw new IllegalArgumentException("testgrid.artifacts.root must be set");
    }
    if (retention.compareTo(Duration.ofMinutes(1)) < 0) {
      throw new IllegalArgumentException("testgrid.artifacts.retention must be at least 1 minute");
    }
    if (linkKey != null && linkKey.isBlank()) {
      linkKey = null;
    }
    if (linkKey != null && linkKey.length() < MIN_LINK_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "TESTGRID_ARTIFACT_LINK_KEY must be at least " + MIN_LINK_KEY_LENGTH + " characters");
    }
    if (linkTtlSeconds < 1 || linkTtlSeconds > MAX_LINK_TTL_SECONDS) {
      throw new IllegalArgumentException("testgrid.artifacts.link-ttl-seconds must be 1..300");
    }
  }

  /** Never print the link key, not even in a config dump. */
  @Override
  public String toString() {
    return "ArtifactProperties[root="
        + root
        + ", retention="
        + retention
        + ", linkKey="
        + (linkKey == null ? "random" : "[REDACTED]")
        + ", linkTtlSeconds="
        + linkTtlSeconds
        + "]";
  }
}

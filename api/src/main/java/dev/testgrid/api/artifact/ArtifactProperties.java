package dev.testgrid.api.artifact;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PRD-06 artifact storage (ADR-005).
 *
 * <ul>
 *   <li>{@code root}: the local store's directory, an operator setting ({@code
 *       TESTGRID_ARTIFACT_ROOT}), never anything a client or worker sends.
 *   <li>{@code retention}: how long an artifact stays readable; {@code expires_at} is computed from
 *       the database clock (ARCHITECTURE §6: 7 days).
 * </ul>
 */
@ConfigurationProperties("testgrid.artifacts")
public record ArtifactProperties(Path root, @DefaultValue("7d") Duration retention) {

  public ArtifactProperties {
    if (root == null) {
      throw new IllegalArgumentException("testgrid.artifacts.root must be set");
    }
    if (retention.isNegative() || retention.compareTo(Duration.ofMinutes(1)) < 0) {
      throw new IllegalArgumentException("testgrid.artifacts.retention must be at least 1 minute");
    }
  }
}

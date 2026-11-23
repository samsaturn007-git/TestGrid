package dev.testgrid.api.artifact;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PRD-06 artifact storage (ADR-005). {@code root} is the local store's directory: an operator
 * setting ({@code TESTGRID_ARTIFACT_ROOT}), never anything a client or worker sends.
 */
@ConfigurationProperties("testgrid.artifacts")
public record ArtifactProperties(Path root) {

  public ArtifactProperties {
    if (root == null) {
      throw new IllegalArgumentException("testgrid.artifacts.root must be set");
    }
  }
}

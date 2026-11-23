package dev.testgrid.api.artifact;

import dev.testgrid.api.problem.ProblemType;
import java.io.IOException;

/**
 * An upload that broke a rule while it streamed: too large ({@code 413 artifact-too-large}), the
 * wrong bytes for its kind ({@code 415 unsupported-media-type}) or a malformed multipart body
 * ({@code 400 validation-failed}). It is an {@link IOException}, so the store's {@code put} sees it
 * as a failed read and deletes the partial file before it propagates.
 */
public class ArtifactRejectedException extends IOException {

  private final ProblemType type;

  public ArtifactRejectedException(ProblemType type, String message) {
    super(message);
    this.type = type;
  }

  public ProblemType type() {
    return type;
  }
}

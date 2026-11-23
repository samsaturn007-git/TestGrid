package dev.testgrid.api.artifact;

import java.io.IOException;

/** A path under the store root that is a symbolic link, or not what the store created there. */
public class UnsafeArtifactPathException extends IOException {

  public UnsafeArtifactPathException(String message) {
    super(message);
  }
}

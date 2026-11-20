package dev.testgrid.api.manifest;

/** The suite manifest can't be used. Thrown at startup so the API refuses to start (FR-2). */
public class InvalidManifestException extends RuntimeException {

  public InvalidManifestException(String message) {
    super(message);
  }

  public InvalidManifestException(String message, Throwable cause) {
    super(message, cause);
  }
}

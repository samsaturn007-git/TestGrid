package dev.testgrid.api.artifact;

/**
 * Contracts §3 {@code artifacts.kind}. The server decides the file extension, the stored MIME type
 * and the size limit from the kind (PRD-06 FR-3); the worker never names any of them.
 */
public enum ArtifactKind {
  SCREENSHOT("png", "image/png", 5L * 1024 * 1024),
  TRACE("zip", "application/zip", 50L * 1024 * 1024),
  LOG("log", "text/plain; charset=utf-8", 1L * 1024 * 1024);

  private final String extension;
  private final String mime;
  private final long maxBytes;

  ArtifactKind(String extension, String mime, long maxBytes) {
    this.extension = extension;
    this.mime = mime;
    this.maxBytes = maxBytes;
  }

  public String extension() {
    return extension;
  }

  public String mime() {
    return mime;
  }

  public long maxBytes() {
    return maxBytes;
  }
}

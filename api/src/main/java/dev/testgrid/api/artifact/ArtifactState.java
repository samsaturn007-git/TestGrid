package dev.testgrid.api.artifact;

/**
 * Contracts §3 {@code artifacts.state}. The V8 trigger allows only forward moves: {@code AVAILABLE
 * → EXPIRED → PURGED}, and {@code AVAILABLE} or {@code EXPIRED → MISSING} when the bytes are gone.
 */
public enum ArtifactState {
  AVAILABLE,
  EXPIRED,
  MISSING,
  PURGED
}

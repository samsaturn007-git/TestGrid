package dev.testgrid.api.quarantine;

/** Contracts §3 {@code quarantines.source}. Only the API creates {@code AUTO} ones. */
public enum QuarantineSource {
  MANUAL,
  SUGGESTED,
  AUTO
}

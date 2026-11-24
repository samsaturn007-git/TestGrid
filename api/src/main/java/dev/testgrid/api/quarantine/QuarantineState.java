package dev.testgrid.api.quarantine;

/**
 * Derived on read from the database clock, never stored: {@code REVOKED} when revoked, else {@code
 * EXPIRED} once {@code expires_at} has passed, else {@code ACTIVE}. Only ACTIVE ones excuse
 * failures.
 */
public enum QuarantineState {
  ACTIVE,
  EXPIRED,
  REVOKED
}

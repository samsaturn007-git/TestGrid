package dev.testgrid.api.domain;

/** Contracts §2.1 {@code ErrorClass}: the raw failure class, set where the failure is observed. */
public enum ErrorClass {
  ASSERTION,
  TEST_TIMEOUT,
  SELECTOR_NOT_FOUND,
  BROWSER_LAUNCH,
  WORKER_LOST,
  WORKER_SHUTDOWN,
  LEASE_EXPIRED,
  NETWORK,
  ARTIFACT_STORE,
  CONFIG,
  CANCELLED,
  UNKNOWN
}

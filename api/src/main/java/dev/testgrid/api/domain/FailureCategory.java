package dev.testgrid.api.domain;

/**
 * Contracts §2.1 {@code FailureCategory} (PRD-07): what a failure group likely is. A label from
 * transparent rules, never proof of a root cause.
 */
public enum FailureCategory {
  PRODUCT_SUSPECTED,
  TEST_BROKEN,
  AUTOMATION_FLAKE,
  TIMEOUT,
  ENVIRONMENT,
  UNKNOWN
}

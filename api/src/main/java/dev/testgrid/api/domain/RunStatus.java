package dev.testgrid.api.domain;

/** Contracts §2.1 {@code RunStatus}. */
public enum RunStatus {
  QUEUED,
  RUNNING,
  PASSED,
  FAILED,
  TIMED_OUT,
  INFRA_ERROR,
  CANCELLED
}

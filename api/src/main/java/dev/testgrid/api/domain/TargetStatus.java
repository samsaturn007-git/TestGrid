package dev.testgrid.api.domain;

/** Contracts §2.1 {@code TargetStatus}. */
public enum TargetStatus {
  QUEUED,
  CLAIMED,
  RUNNING,
  PASSED,
  FAILED,
  TIMED_OUT,
  INFRA_ERROR,
  CANCELLED
}

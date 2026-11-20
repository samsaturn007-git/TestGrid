package dev.testgrid.api.domain;

/** Contracts §2.1 {@code CaseStatus}. {@code FLAKY} = passed after an in-run Playwright retry. */
public enum CaseStatus {
  PASSED,
  FAILED,
  TIMED_OUT,
  SKIPPED,
  FLAKY
}

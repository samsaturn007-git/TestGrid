package dev.testgrid.api.domain;

/** Contracts §2.1 {@code Trigger}. */
public enum Trigger {
  MANUAL,
  CI,
  RERUN,
  BENCHMARK,
  CHAOS
}

package dev.testgrid.api.worker;

/** Contracts §3 {@code workers.state} (PRD-04 FR-7). */
public enum WorkerState {
  ACTIVE,
  DRAINING,
  LOST
}

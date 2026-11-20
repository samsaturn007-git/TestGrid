package dev.testgrid.api.domain;

import java.util.EnumSet;
import java.util.Set;

/** Contracts §2.1 {@code AttemptStatus}. */
public enum AttemptStatus {
  CLAIMED,
  RUNNING,
  PASSED,
  FAILED,
  TIMED_OUT,
  INFRA_ERROR,
  CANCELLED,
  ABANDONED;

  public static final Set<AttemptStatus> ACTIVE = EnumSet.of(CLAIMED, RUNNING);

  public boolean isActive() {
    return ACTIVE.contains(this);
  }
}

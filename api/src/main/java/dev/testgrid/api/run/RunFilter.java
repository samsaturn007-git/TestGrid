package dev.testgrid.api.run;

import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.Trigger;
import java.util.Set;

/**
 * GET /runs filters (PRD-05 T3). An empty set or a null means "any". Values within one filter are
 * ORed ({@code status=FAILED&status=TIMED_OUT}); different filters are ANDed.
 */
public record RunFilter(
    Set<RunStatus> statuses, String suiteKey, String branch, Set<Trigger> triggers) {

  public static final RunFilter NONE = new RunFilter(Set.of(), null, null, Set.of());

  public RunFilter {
    statuses = Set.copyOf(statuses);
    triggers = Set.copyOf(triggers);
  }
}

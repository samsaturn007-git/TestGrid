package dev.testgrid.api.casehealth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PRD-07 FR-4: the rolling window and its thresholds (contracts §8.3). A smaller window after a
 * restart applies on each case's next result; a larger one fills up as results arrive.
 */
@ConfigurationProperties("testgrid.case-health")
public record CaseHealthProperties(
    @DefaultValue("20") int windowSize,
    @DefaultValue("5") int minObservations,
    @DefaultValue("0.2") double flipRateThreshold,
    @DefaultValue("2") int flakyStatuses,
    @DefaultValue("3") int failingStreak) {

  public static final int MAX_WINDOW = 100;

  public CaseHealthProperties {
    if (windowSize < 5 || windowSize > MAX_WINDOW) {
      throw new IllegalArgumentException("testgrid.case-health.window-size must be 5..100");
    }
    if (minObservations < 2 || minObservations > windowSize) {
      throw new IllegalArgumentException(
          "testgrid.case-health.min-observations must be 2..window-size");
    }
    if (!(flipRateThreshold > 0 && flipRateThreshold <= 1)) {
      throw new IllegalArgumentException(
          "testgrid.case-health.flip-rate-threshold must be above 0 and at most 1");
    }
    if (flakyStatuses < 1 || flakyStatuses > windowSize) {
      throw new IllegalArgumentException(
          "testgrid.case-health.flaky-statuses must be 1..window-size");
    }
    if (failingStreak < 1 || failingStreak > windowSize) {
      throw new IllegalArgumentException(
          "testgrid.case-health.failing-streak must be 1..window-size");
    }
  }
}

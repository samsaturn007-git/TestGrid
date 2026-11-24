package dev.testgrid.api.compare;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Contracts §8 duration-regression thresholds: slower than base × ratio and base + min delta. */
@ConfigurationProperties("testgrid.compare")
public record CompareProperties(
    @DefaultValue("1.5") double regressionRatio, @DefaultValue("500") int regressionMinDeltaMs) {

  public CompareProperties {
    if (!(regressionRatio > 1 && regressionRatio <= 10)) {
      throw new IllegalArgumentException(
          "testgrid.compare.regression-ratio must be above 1, at most 10");
    }
    if (regressionMinDeltaMs < 0 || regressionMinDeltaMs > 600_000) {
      throw new IllegalArgumentException(
          "testgrid.compare.regression-min-delta-ms must be 0..600000");
    }
  }

  public RunComparison.Thresholds thresholds() {
    return new RunComparison.Thresholds(regressionRatio, regressionMinDeltaMs);
  }
}

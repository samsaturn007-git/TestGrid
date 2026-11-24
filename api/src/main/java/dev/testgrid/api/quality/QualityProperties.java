package dev.testgrid.api.quality;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The quality loop (CONFLICT_REGISTER N35): evaluates each run as it finishes and, every {@code
 * sweepIntervalMs}, up to {@code sweepBatchSize} runs finished in the last day with no evaluation.
 */
@ConfigurationProperties("testgrid.quality")
public record QualityProperties(
    @DefaultValue("true") boolean loopEnabled,
    @DefaultValue("5000") long sweepIntervalMs,
    @DefaultValue("50") int sweepBatchSize) {

  public QualityProperties {
    if (sweepIntervalMs < 100 || sweepIntervalMs > 600_000) {
      throw new IllegalArgumentException("testgrid.quality.sweep-interval-ms must be 100..600000");
    }
    if (sweepBatchSize < 1 || sweepBatchSize > 1000) {
      throw new IllegalArgumentException("testgrid.quality.sweep-batch-size must be 1..1000");
    }
  }
}

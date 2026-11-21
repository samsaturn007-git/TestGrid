package dev.testgrid.api.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Lease reaper timings (PRD-04 FR-6, ARCHITECTURE §4 and §6). The lease TTL and {@code
 * max_attempts} come from {@code scheduler_settings}; these only say how often the reaper looks and
 * how long a target waits before its next attempt.
 *
 * <ul>
 *   <li>{@code retryBackoff*}: after the n-th attempt is abandoned the target waits {@code base ×
 *       2ⁿ⁻¹} (2 s, 4 s, 8 s, …), capped at {@code max}, ±20 % jitter.
 *   <li>{@code dispatchTimeoutMs}: a target dispatched this long ago and still unclaimed has lost
 *       its message (dead-lettered, or a retry message lost on the way back; CONFLICT_REGISTER
 *       N17). It is dispatched again with a new generation. It must be longer than the longest time
 *       a message can legitimately wait in a queue for a free worker slot.
 * </ul>
 */
@ConfigurationProperties("testgrid.reaper")
public record ReaperProperties(
    @DefaultValue("5000") long intervalMs,
    @DefaultValue("100") int batchSize,
    @DefaultValue("2000") long retryBackoffBaseMs,
    @DefaultValue("60000") long retryBackoffMaxMs,
    @DefaultValue("300000") long dispatchTimeoutMs) {

  /** ±20 %. */
  static final double JITTER = 0.2;

  public ReaperProperties {
    if (intervalMs < 10) {
      throw new IllegalArgumentException("testgrid.reaper.interval-ms must be >= 10");
    }
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("testgrid.reaper.batch-size must be 1..1000");
    }
    if (retryBackoffBaseMs < 1 || retryBackoffMaxMs < retryBackoffBaseMs) {
      throw new IllegalArgumentException("testgrid.reaper retry backoff must be 1 <= base <= max");
    }
    if (dispatchTimeoutMs < 1000) {
      throw new IllegalArgumentException("testgrid.reaper.dispatch-timeout-ms must be >= 1000");
    }
  }

  /**
   * Delay before the attempt after attempt {@code attemptNo}. {@code random} is uniform in [0, 1);
   * 0.5 gives the exact value, 0 and 1 the ends of the ±20 % band.
   */
  public long retryBackoffMs(int attemptNo, double random) {
    int exponent = Math.min(Math.max(attemptNo - 1, 0), 30);
    long base = Math.min(retryBackoffBaseMs << exponent, retryBackoffMaxMs);
    double factor = 1 - JITTER + 2 * JITTER * random;
    return Math.max(1, Math.round(base * factor));
  }
}

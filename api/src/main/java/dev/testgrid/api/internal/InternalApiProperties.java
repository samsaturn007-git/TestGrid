package dev.testgrid.api.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The worker API (contracts §7). {@code workerToken} comes from {@code TESTGRID_WORKER_TOKEN}; when
 * it is unset the internal port is not opened at all and every {@code /internal/*} path is a 404.
 * Lease timings follow ARCHITECTURE §6 until {@code scheduler_settings} arrives in PRD-04.
 */
@ConfigurationProperties("testgrid.internal")
public record InternalApiProperties(
    @DefaultValue("8081") int port,
    String workerToken,
    @DefaultValue("30") int leaseTtlSeconds,
    @DefaultValue("10") int heartbeatIntervalSeconds,
    @DefaultValue("60000") int completionGraceMs) {

  static final int MIN_TOKEN_LENGTH = 32;

  public InternalApiProperties {
    if (workerToken != null && workerToken.isBlank()) {
      workerToken = null;
    }
    if (workerToken != null && workerToken.length() < MIN_TOKEN_LENGTH) {
      throw new IllegalArgumentException(
          "TESTGRID_WORKER_TOKEN must be at least " + MIN_TOKEN_LENGTH + " characters");
    }
    if (heartbeatIntervalSeconds <= 0 || leaseTtlSeconds <= heartbeatIntervalSeconds) {
      throw new IllegalArgumentException("lease TTL must be longer than the heartbeat interval");
    }
  }

  public boolean enabled() {
    return workerToken != null;
  }

  /** Never print the token, not even in a config dump. */
  @Override
  public String toString() {
    return "InternalApiProperties[port=%d, workerToken=%s, leaseTtlSeconds=%d]"
        .formatted(port, enabled() ? "<set>" : "<unset>", leaseTtlSeconds);
  }
}

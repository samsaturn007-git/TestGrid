package dev.testgrid.api.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The worker API (contracts §7). {@code workerToken} comes from {@code TESTGRID_WORKER_TOKEN}; when
 * it is unset the internal port is not opened at all and every {@code /internal/*} path is a 404.
 * The lease TTL lives in {@code scheduler_settings} (PRD-04, CONFLICT_REGISTER N15); only the
 * heartbeat interval the worker is told to use stays a property.
 */
@ConfigurationProperties("testgrid.internal")
public record InternalApiProperties(
    @DefaultValue("8081") int port,
    String workerToken,
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
    if (heartbeatIntervalSeconds <= 0) {
      throw new IllegalArgumentException("the heartbeat interval must be positive");
    }
  }

  public boolean enabled() {
    return workerToken != null;
  }

  /** Never print the token, not even in a config dump. */
  @Override
  public String toString() {
    return "InternalApiProperties[port=%d, workerToken=%s, heartbeatIntervalSeconds=%d]"
        .formatted(port, enabled() ? "<set>" : "<unset>", heartbeatIntervalSeconds);
  }
}

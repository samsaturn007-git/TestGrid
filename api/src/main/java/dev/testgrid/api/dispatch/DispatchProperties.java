package dev.testgrid.api.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Timings for the dispatcher and the outbox publisher (ARCHITECTURE §6). {@code enabled=false}
 * stops both background loops (tests drive {@link Dispatcher} and {@link OutboxPublisher}
 * directly); the cap and retries come from {@code scheduler_settings}, not from here.
 */
@ConfigurationProperties("testgrid.dispatch")
public record DispatchProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("250") long tickMs,
    @DefaultValue("500") long outboxPollMs,
    @DefaultValue("100") int outboxBatchSize,
    @DefaultValue("5000") long confirmTimeoutMs,
    @DefaultValue("1000") long publishBackoffBaseMs,
    @DefaultValue("30000") long publishBackoffMaxMs) {

  public DispatchProperties {
    if (tickMs < 10 || outboxPollMs < 10) {
      throw new IllegalArgumentException("testgrid.dispatch tick and poll must be >= 10 ms");
    }
    if (outboxBatchSize < 1 || outboxBatchSize > 1000) {
      throw new IllegalArgumentException("testgrid.dispatch.outbox-batch-size must be 1..1000");
    }
    if (confirmTimeoutMs < 100) {
      throw new IllegalArgumentException("testgrid.dispatch.confirm-timeout-ms must be >= 100");
    }
    if (publishBackoffBaseMs < 1 || publishBackoffMaxMs < publishBackoffBaseMs) {
      throw new IllegalArgumentException(
          "testgrid.dispatch publish backoff must be 1 <= base <= max");
    }
  }

  /** Delay before the next publish try after {@code attempts} failed ones: base × 2ⁿ, capped. */
  public long publishBackoffMs(int attempts) {
    int exponent = Math.min(Math.max(attempts - 1, 0), 30);
    return Math.min(publishBackoffBaseMs << exponent, publishBackoffMaxMs);
  }
}

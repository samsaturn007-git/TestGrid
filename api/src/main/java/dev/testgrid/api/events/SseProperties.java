package dev.testgrid.api.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Run event streams (PRD-05 FR-4, contracts §5).
 *
 * <ul>
 *   <li>{@code maxStreams}: open streams across all runs; one more is {@code 429 rate-limited}.
 *   <li>{@code pingIntervalMs}: a {@code : ping} comment keeps proxies from closing an idle stream;
 *       each ping also re-reads the run's events, so a lost NOTIFY costs at most one interval.
 *   <li>{@code replayLimit}: a client more than this many events behind gets one {@code RESYNC}.
 *   <li>{@code timeoutMs}: a stream is closed after this long; EventSource reconnects with {@code
 *       Last-Event-ID} and loses nothing.
 *   <li>{@code listen}: LISTEN for NOTIFY at all (tests turn it off to prove the ping sweep).
 * </ul>
 */
@ConfigurationProperties("testgrid.sse")
public record SseProperties(
    @DefaultValue("100") int maxStreams,
    @DefaultValue("15000") long pingIntervalMs,
    @DefaultValue("1000") int replayLimit,
    @DefaultValue("1800000") long timeoutMs,
    @DefaultValue("true") boolean listen) {

  public SseProperties {
    if (maxStreams < 1) {
      throw new IllegalArgumentException("testgrid.sse.max-streams must be >= 1");
    }
    if (pingIntervalMs < 50) {
      throw new IllegalArgumentException("testgrid.sse.ping-interval-ms must be >= 50");
    }
    if (replayLimit < 1 || replayLimit > 10_000) {
      throw new IllegalArgumentException("testgrid.sse.replay-limit must be 1..10000");
    }
    if (timeoutMs <= pingIntervalMs) {
      throw new IllegalArgumentException("testgrid.sse.timeout-ms must exceed the ping interval");
    }
  }
}

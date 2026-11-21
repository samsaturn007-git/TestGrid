package dev.testgrid.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** ARCHITECTURE §6 retry backoff: 2 s × 2ⁿ⁻¹ after the n-th attempt, capped, ±20 % jitter. */
class ReaperPropertiesTest {

  private final ReaperProperties defaults = new ReaperProperties(5000, 100, 2000, 60_000, 300_000);

  @Test
  void backoffDoublesPerAttemptUpToTheCap() {
    assertThat(
            IntStream.rangeClosed(1, 8).mapToLong(n -> defaults.retryBackoffMs(n, 0.5)).toArray())
        .containsExactly(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L, 60000L);
    assertThat(defaults.retryBackoffMs(0, 0.5)).isEqualTo(2000);
    assertThat(defaults.retryBackoffMs(Integer.MAX_VALUE, 0.5)).isEqualTo(60000);
  }

  @Test
  void jitterStaysWithinTwentyPercent() {
    assertThat(defaults.retryBackoffMs(1, 0.0)).isEqualTo(1600);
    assertThat(defaults.retryBackoffMs(1, 0.999_999)).isEqualTo(2400);
    assertThat(defaults.retryBackoffMs(6, 0.0)).isEqualTo(48000);
  }

  @Test
  void invalidSettingsRefuseToStart() {
    assertThatThrownBy(() -> new ReaperProperties(5, 100, 2000, 60_000, 300_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReaperProperties(5000, 0, 2000, 60_000, 300_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReaperProperties(5000, 100, 2000, 1000, 300_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReaperProperties(5000, 100, 2000, 60_000, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

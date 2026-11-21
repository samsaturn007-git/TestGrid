package dev.testgrid.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Publish backoff (contracts §9): 1 s × 2ⁿ⁻¹ after the n-th failure, capped at 30 s. */
class DispatchPropertiesTest {

  private final DispatchProperties defaults =
      new DispatchProperties(true, 250, 500, 100, 5000, 1000, 30000);

  @Test
  void backoffDoublesFromTheBaseAndStopsAtTheCap() {
    assertThat(defaults.publishBackoffMs(1)).isEqualTo(1000);
    assertThat(defaults.publishBackoffMs(2)).isEqualTo(2000);
    assertThat(defaults.publishBackoffMs(5)).isEqualTo(16000);
    assertThat(defaults.publishBackoffMs(6)).isEqualTo(30000);
    // Large counts don't overflow the shift.
    assertThat(defaults.publishBackoffMs(Integer.MAX_VALUE)).isEqualTo(30000);
  }

  @Test
  void refusesSettingsThatWouldSpinOrNeverConfirm() {
    assertThatThrownBy(() -> new DispatchProperties(true, 1, 500, 100, 5000, 1000, 30000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DispatchProperties(true, 250, 500, 0, 5000, 1000, 30000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DispatchProperties(true, 250, 500, 100, 10, 1000, 30000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DispatchProperties(true, 250, 500, 100, 5000, 2000, 1000))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

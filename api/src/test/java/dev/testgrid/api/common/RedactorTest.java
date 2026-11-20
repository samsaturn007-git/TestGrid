package dev.testgrid.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactorTest {

  @Test
  void masksBearerHeadersAndLongOpaqueStrings() {
    String token = "Ab3_-".repeat(9).substring(0, 43);
    assertThat(Redactor.redact("Authorization: Bearer abc.def-123 failed"))
        .isEqualTo("Authorization: Bearer [REDACTED] failed");
    assertThat(Redactor.redact("lease " + token + " rejected"))
        .isEqualTo("lease [REDACTED] rejected");
  }

  @Test
  void keepsOrdinaryPlaywrightMessages() {
    String message = "expect(locator).toHaveText(expected) failed\nExpected: \"$80.99\"";
    assertThat(Redactor.redact(message)).isEqualTo(message);
  }

  @Test
  void stripsControlCharactersAndCapsTheLength() {
    assertThat(Redactor.redact("a\u001b[31mred\u0000")).isEqualTo("a[31mred");
    assertThat(Redactor.redact("x ".repeat(3000))).hasSize(2000).endsWith("…");
    assertThat(Redactor.redact(null)).isNull();
  }
}

package dev.testgrid.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactorTest {

  @Test
  void masksBearerHeadersAndLongOpaqueStrings() {
    String token = "Ab3_-".repeat(9).substring(0, 43);
    // PRD-06 FR-5: an Authorization header loses its whole value, scheme included.
    assertThat(Redactor.redact("Authorization: Bearer abc.def-123 failed"))
        .isEqualTo("Authorization: [REDACTED]");
    assertThat(Redactor.redact("sent bearer abc.def-123 failed"))
        .isEqualTo("sent Bearer [REDACTED] failed");
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

  @Test
  void prd06MasksAuthorizationAndCookieHeaderValues() {
    assertThat(Redactor.redact("request had Authorization: Basic dXNlcjpwYXNz and more"))
        .isEqualTo("request had Authorization: [REDACTED]");
    assertThat(Redactor.redact("Cookie: session=abc123; tg_canary=xyz\nnext line"))
        .isEqualTo("Cookie: [REDACTED]\nnext line");
    assertThat(Redactor.redact("{\"set-cookie\": \"sid=s3cr3t; HttpOnly\"}"))
        .isEqualTo("{\"set-cookie\": \"[REDACTED]\"}");
  }

  @Test
  void prd06MasksJwtShapedStrings() {
    String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl";
    assertThat(Redactor.redact("token " + jwt + " expired")).isEqualTo("token [REDACTED] expired");
  }

  @Test
  void prd06MasksValuesOfSecretNamedEnvironmentVariables() {
    List<String> secrets =
        Redactor.secretsFrom(
            Map.of(
                "TESTGRID_CANARY_SECRET", "tg-canary-0f8f",
                "DB_PASSWORD", "hunter2-long",
                "API_KEY", "short",
                "HOME", "/home/pwuser-not-secret",
                "KEYBOARD", "not-a-secret-name"));
    assertThat(secrets).containsExactly("tg-canary-0f8f", "hunter2-long");
    assertThat(Redactor.redact("page said tg-canary-0f8f and hunter2-long; short stays", secrets))
        .isEqualTo("page said [REDACTED] and [REDACTED]; short stays");
  }
}

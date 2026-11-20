package dev.testgrid.api.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.problem.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunCursorTest {

  @Test
  void roundTripsAtMicrosecondPrecision() {
    RunCursor cursor =
        new RunCursor(Instant.parse("2026-11-20T01:02:03.123456Z"), UUID.randomUUID());
    String encoded = cursor.encode();
    assertThat(encoded).matches("^[A-Za-z0-9_-]+$");
    assertThat(RunCursor.decode(encoded)).isEqualTo(cursor);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-base64!",
        "djF8MTIzfG5vdC1hLXV1aWQ",
        "",
        "x",
        "djJ8MXwwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDA"
      })
  void rejectsCursorsItDidNotIssue(String cursor) {
    assertThatThrownBy(() -> RunCursor.decode(cursor))
        .isInstanceOf(ApiProblemException.class)
        .hasMessageContaining("cursor");
  }

  /** A forged but well-formed cursor only moves the page position; an unparsable one is refused. */
  @Test
  void rejectsATimestampOutsideTheLongRange() {
    String raw = "v1|9223372036854775808|" + UUID.randomUUID();
    String cursor =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> RunCursor.decode(cursor)).isInstanceOf(ApiProblemException.class);
  }
}

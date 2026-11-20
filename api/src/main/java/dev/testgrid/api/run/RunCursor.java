package dev.testgrid.api.run;

import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Keyset cursor for GET /runs (PRD-02 FR-6): the {@code (queued_at, id)} of the last item served,
 * as base64url of {@code v1|<epoch microseconds>|<uuid>}. Microseconds match Postgres timestamptz
 * precision exactly, so the next page starts strictly after that row with no rounding gap.
 */
public record RunCursor(Instant queuedAt, UUID id) {

  private static final String VERSION = "v1";

  public String encode() {
    long micros = ChronoUnit.MICROS.between(Instant.EPOCH, queuedAt);
    String raw = VERSION + "|" + micros + "|" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static RunCursor decode(String cursor) {
    try {
      if (cursor.length() > 200) {
        throw new IllegalArgumentException("too long");
      }
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split("\\|", -1);
      if (parts.length != 3 || !parts[0].equals(VERSION)) {
        throw new IllegalArgumentException("unknown cursor format");
      }
      Instant queuedAt = Instant.EPOCH.plus(Long.parseLong(parts[1]), ChronoUnit.MICROS);
      return new RunCursor(queuedAt, UUID.fromString(parts[2]));
    } catch (IllegalArgumentException | ArithmeticException | DateTimeException e) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED,
          "cursor is not one this API issued",
          List.of(new FieldError("cursor", "invalid cursor")));
    }
  }
}

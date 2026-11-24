package dev.testgrid.api.failure;

import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Keyset cursor for GET /failure-groups: the {@code (occurrence_count, id)} of the last group
 * served, as base64url of {@code v1|<count>|<uuid>}. Counts grow, so a group that gains occurrences
 * while a client pages can move ahead of the cursor; pages never repeat a row.
 */
public record FailureGroupCursor(int occurrenceCount, UUID id) {

  private static final String VERSION = "v1";

  public String encode() {
    String raw = VERSION + "|" + occurrenceCount + "|" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static FailureGroupCursor decode(String cursor) {
    try {
      if (cursor.length() > 200) {
        throw new IllegalArgumentException("too long");
      }
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split("\\|", -1);
      if (parts.length != 3 || !parts[0].equals(VERSION)) {
        throw new IllegalArgumentException("unknown cursor format");
      }
      int count = Integer.parseInt(parts[1]);
      if (count < 1) {
        throw new IllegalArgumentException("count");
      }
      return new FailureGroupCursor(count, UUID.fromString(parts[2]));
    } catch (IllegalArgumentException e) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED,
          "cursor is not one this API issued",
          List.of(new FieldError("cursor", "invalid cursor")));
    }
  }
}

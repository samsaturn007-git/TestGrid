package dev.testgrid.api.artifact;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Storage keys are generated here and nowhere else (ADR-005): {@code yyyy/mm/dd/<uuid>.<ext>}, date
 * in UTC. Every key a store is asked to touch must match {@link #PATTERN} exactly, so {@code ..}, a
 * leading {@code /}, a backslash or a NUL never reaches a filesystem call.
 */
public final class ArtifactKeys {

  public static final Pattern PATTERN =
      Pattern.compile(
          "^[0-9]{4}/(0[1-9]|1[0-2])/(0[1-9]|[12][0-9]|3[01])/"
              + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|zip|log)$");

  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

  private ArtifactKeys() {}

  public static String newKey(ArtifactKind kind, Instant at) {
    return DAY.format(at) + "/" + UUID.randomUUID() + "." + kind.extension();
  }

  public static boolean isValid(String key) {
    return key != null && PATTERN.matcher(key).matches();
  }

  /** Throws for anything that is not a key this server could have generated. */
  public static String require(String key) {
    if (!isValid(key)) {
      throw new IllegalArgumentException("not a valid artifact storage key");
    }
    return key;
  }
}

package dev.testgrid.api.common;

import java.security.SecureRandom;
import java.util.HexFormat;

/** W3C Trace Context {@code traceparent} values (version 00, sampled flag set). */
public final class Traceparent {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final HexFormat HEX = HexFormat.of();

  private Traceparent() {}

  /** A new root: random trace and span IDs, never all zero. */
  public static String newRoot() {
    return "00-" + nonZeroHex(16) + "-" + nonZeroHex(8) + "-01";
  }

  /** The 32-hex trace ID inside a valid {@code traceparent}. */
  public static String traceId(String traceparent) {
    return traceparent.substring(3, 35);
  }

  private static String nonZeroHex(int bytes) {
    byte[] value = new byte[bytes];
    do {
      RANDOM.nextBytes(value);
    } while (allZero(value));
    return HEX.formatHex(value);
  }

  private static boolean allZero(byte[] value) {
    for (byte b : value) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}

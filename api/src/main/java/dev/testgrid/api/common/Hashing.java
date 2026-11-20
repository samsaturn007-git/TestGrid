package dev.testgrid.api.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {

  private Hashing() {}

  public static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
    }
  }

  public static byte[] sha256(String input) {
    return sha256(input.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256Hex(byte[] input) {
    return HexFormat.of().formatHex(sha256(input));
  }
}

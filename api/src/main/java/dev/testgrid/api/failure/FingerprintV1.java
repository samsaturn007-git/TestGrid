package dev.testgrid.api.failure;

import dev.testgrid.api.common.Hashing;
import dev.testgrid.api.domain.ErrorClass;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PRD-07 FR-1, fingerprint algorithm {@code fp_v1} (contracts §8.1): the same failure seen in
 * another run, with other order numbers, timestamps or temp paths, gets the same 16-hex
 * fingerprint. Pure and stateless, so a future {@code fp_v2} can sit next to it.
 */
public final class FingerprintV1 {

  public static final String ALGO_VERSION = "fp_v1";
  public static final int MAX_FRAMES = 3;

  /**
   * One stack frame as the worker sends it (CONFLICT_REGISTER N24): a path relative to the worker
   * root, no {@code ..} segment, then the function name, never a line number.
   */
  public static final String FRAME =
      "^(?!/)(?!(?:.*/)?\\.\\.(?:/|:))[A-Za-z0-9._/-]{1,160}:[A-Za-z_$<][A-Za-z0-9_$.<>-]{0,79}$";

  private static final Pattern ANSI =
      Pattern.compile(
          "\\u001B\\[[0-?]*[ -/]*[@-~]|\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)");
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
  // Playwright's call log lists every retry of the locator, so its length depends on timing.
  private static final Pattern CALL_LOG = Pattern.compile("\\n[ \\t]*Call log:[\\s\\S]*\\z");
  private static final Pattern URL_QUERY =
      Pattern.compile("(\\b[A-Za-z][A-Za-z0-9+.-]*://[^\\s?#\"'<>]*)\\?[^\\s#\"'<>)]*");
  private static final Pattern UUID =
      Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
  private static final Pattern TIMESTAMP =
      Pattern.compile(
          "\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?\\b"
              + "|\\b\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b");
  private static final Pattern TEMP_PATH =
      Pattern.compile(
          "(?:/private)?/var/folders/[^\\s\"'<>:)]*"
              + "|/tmp/[^\\s\"'<>:)]*"
              + "|(?i:[a-z]:\\\\[^\\s\"'<>]*\\\\temp\\\\[^\\s\"'<>:)]*)"
              + "|\\btest-results/[^\\s\"'<>:)]*");
  private static final Pattern HEX =
      Pattern.compile(
          "(?i)\\b0x[0-9a-f]+\\b|\\b(?=[0-9a-f]*[0-9])(?=[0-9a-f]*[a-f])[0-9a-f]{8,}\\b");
  private static final Pattern NUMBER = Pattern.compile("\\d+");
  private static final Pattern SPACES = Pattern.compile("[ \\t]+");
  private static final Pattern BLANK_EDGES = Pattern.compile("[ ]*\\n[ \\n]*");

  private FingerprintV1() {}

  public record Fingerprint(String algoVersion, String value, String normalisedMessage) {}

  public static Fingerprint of(ErrorClass errorClass, List<String> frames, String message) {
    Objects.requireNonNull(errorClass, "errorClass");
    List<String> kept = frames == null ? List.of() : frames.stream().limit(MAX_FRAMES).toList();
    for (String frame : kept) {
      if (frame == null || !frame.matches(FRAME)) {
        throw new IllegalArgumentException("not a file:function frame");
      }
    }
    String normalised = normalise(message);
    // Frames can't hold '|' or ';' and the error class is an enum, so the message (last, free
    // text) can never shift a boundary: distinct inputs give distinct hash inputs.
    String input =
        ALGO_VERSION + "|" + errorClass.name() + "|" + String.join(";", kept) + "|" + normalised;
    return new Fingerprint(
        ALGO_VERSION,
        Hashing.sha256Hex(input.getBytes(StandardCharsets.UTF_8)).substring(0, 16),
        normalised);
  }

  public static String stripAnsi(String text) {
    return text == null ? null : ANSI.matcher(text).replaceAll("");
  }

  /**
   * Repeats one pass until nothing changes, so {@code normalise(normalise(x)) == normalise(x)}
   * holds even where a placeholder creates a new word boundary. No rule adds a digit, a '/' or a
   * tab; a rule that lengthens the text (a digit becomes {@code <N>}) removes a digit, and every
   * other change removes a '/' or a tab or shortens the text. So (digits + slashes + tabs, length)
   * falls with each changing pass and the loop ends. A pass is O(n) and stored messages are at most
   * 2,000 characters.
   */
  public static String normalise(String message) {
    if (message == null) {
      return "";
    }
    String current = message;
    while (true) {
      String next = pass(current);
      if (next.equals(current)) {
        return next;
      }
      current = next;
    }
  }

  private static String pass(String text) {
    String out = ANSI.matcher(text).replaceAll("");
    out = CONTROL.matcher(out).replaceAll("");
    out = CALL_LOG.matcher(out).replaceAll("");
    out = URL_QUERY.matcher(out).replaceAll("$1");
    out = UUID.matcher(out).replaceAll("<UUID>");
    out = TIMESTAMP.matcher(out).replaceAll("<TS>");
    out = TEMP_PATH.matcher(out).replaceAll("<TMP>");
    out = HEX.matcher(out).replaceAll("<HEX>");
    out = NUMBER.matcher(out).replaceAll("<N>");
    out = SPACES.matcher(out).replaceAll(" ");
    out = BLANK_EDGES.matcher(out).replaceAll("\n");
    return out.strip();
  }
}

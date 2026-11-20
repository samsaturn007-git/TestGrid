package dev.testgrid.api.common;

import java.util.regex.Pattern;

/**
 * Cleans worker-supplied error text before it is stored: credentials-looking strings are replaced
 * and the text is cut to the column limit. Error messages come from Playwright output, which could
 * echo a header or an environment value back.
 */
public final class Redactor {

  public static final String MASK = "[REDACTED]";
  static final int MAX_LENGTH = 2000;

  private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
  // A lease token is 43 base64url characters; also catches other long opaque secrets.
  private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_-]{40,}");
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

  private Redactor() {}

  public static String redact(String text) {
    if (text == null) {
      return null;
    }
    String out = CONTROL.matcher(text).replaceAll("");
    out = BEARER.matcher(out).replaceAll("Bearer " + MASK);
    out = OPAQUE.matcher(out).replaceAll(MASK);
    return out.length() <= MAX_LENGTH ? out : out.substring(0, MAX_LENGTH - 1) + "…";
  }
}

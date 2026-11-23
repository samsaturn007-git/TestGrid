package dev.testgrid.api.common;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans worker-supplied error text before it is stored (PRD-06 FR-5: the worker redacts first,
 * this is the second line). Credential-looking strings are replaced and the text is cut to the
 * column limit. Error messages come from Playwright output, which could echo a header, a cookie or
 * an environment value back.
 */
public final class Redactor {

  public static final String MASK = "[REDACTED]";
  static final int MAX_LENGTH = 2000;
  static final int MIN_SECRET_LENGTH = 8;

  /** FR-5: environment variables whose values are secrets ({@code *_TOKEN}, {@code *_KEY}, …). */
  static final Pattern SECRET_ENV_NAME = Pattern.compile("(^|_)(TOKEN|KEY|SECRET|PASSWORD)$");

  private static final Pattern HEADER =
      Pattern.compile(
          "(?i)\\b(authorization|proxy-authorization|cookie|set-cookie)([\"']?\\s*[:=]\\s*[\"']?)[^\\r\\n\"']+");
  private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
  private static final Pattern JWT =
      Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
  // A lease token is 43 base64url characters; also catches other long opaque secrets.
  private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_-]{40,}");
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

  // Read once: this process's own secrets (DB password, worker token, a canary in a drill).
  private static final List<String> ENV_SECRETS = secretsFrom(System.getenv());

  private Redactor() {}

  public static String redact(String text) {
    return redact(text, ENV_SECRETS);
  }

  static String redact(String text, List<String> secrets) {
    if (text == null) {
      return null;
    }
    String out = CONTROL.matcher(text).replaceAll("");
    for (String secret : secrets) {
      out = out.replace(secret, MASK);
    }
    out =
        HEADER
            .matcher(out)
            .replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + MASK));
    out = BEARER.matcher(out).replaceAll("Bearer " + MASK);
    out = JWT.matcher(out).replaceAll(MASK);
    out = OPAQUE.matcher(out).replaceAll(MASK);
    return out.length() <= MAX_LENGTH ? out : out.substring(0, MAX_LENGTH - 1) + "…";
  }

  /**
   * Values of secret-named variables, longest first (a longer secret may contain a shorter one).
   */
  static List<String> secretsFrom(Map<String, String> env) {
    return env.entrySet().stream()
        .filter(e -> SECRET_ENV_NAME.matcher(e.getKey()).find())
        .map(Map.Entry::getValue)
        .filter(v -> v != null && v.length() >= MIN_SECRET_LENGTH)
        .distinct()
        .sorted(Comparator.comparingInt(String::length).reversed())
        .toList();
  }
}

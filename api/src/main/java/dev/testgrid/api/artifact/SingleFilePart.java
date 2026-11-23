package dev.testgrid.api.artifact;

import dev.testgrid.api.problem.ProblemType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeType;

/**
 * Streams the one file part of a {@code multipart/form-data} body (RFC 7578) without buffering it
 * (PRD-06 FR-3). The body must be exactly: the opening boundary, part headers with {@code
 * Content-Disposition: form-data; name="file"}, the bytes, then the closing boundary. A preamble, a
 * second part or a missing close is malformed. The part's own Content-Type is ignored: the kind's
 * magic bytes decide (ADR-005).
 *
 * <p>Delimiter search is a plain scan of a 64 KB window: O(n·m) in the worst case for n body bytes
 * and an m ≤ 74 byte delimiter, about O(n) on real data. The window keeps m − 1 bytes back so a
 * delimiter split across two reads is still found, so memory stays O(window) whatever the size.
 */
public final class SingleFilePart {

  public static final String FIELD = "file";
  static final int MAX_HEADER_BYTES = 8 * 1024;
  private static final int WINDOW = 64 * 1024;
  // RFC 2046 §5.1.1: 1–70 characters from bchars, not ending in a space.
  private static final Pattern BOUNDARY =
      Pattern.compile("^[0-9A-Za-z'()+_,./:=? -]{0,69}[0-9A-Za-z'()+_,./:=?-]$");
  private static final Pattern NAME =
      Pattern.compile("(?i)^form-data\\s*;(?:.*;)?\\s*name=\"([^\"]*)\"(?:\\s*;.*)?$");

  private SingleFilePart() {}

  /** The boundary of a {@code multipart/form-data} content type, or null if it isn't one. */
  public static String boundary(String contentType) {
    if (contentType == null) {
      return null;
    }
    MimeType type;
    try {
      type = MimeType.valueOf(contentType);
    } catch (InvalidMimeTypeException e) {
      return null;
    }
    if (!"multipart".equals(type.getType()) || !"form-data".equals(type.getSubtype())) {
      return null;
    }
    String boundary = type.getParameter("boundary");
    if (boundary != null && boundary.length() >= 2 && boundary.startsWith("\"")) {
      boundary = boundary.substring(1, boundary.length() - 1);
    }
    return boundary != null && BOUNDARY.matcher(boundary).matches() ? boundary : null;
  }

  /** Reads up to the start of the file's bytes and returns a stream that ends at its boundary. */
  public static InputStream open(InputStream raw, String boundary) throws IOException {
    byte[] opening = ("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII);
    if (!Arrays.equals(raw.readNBytes(opening.length), opening)) {
      throw malformed("the body must start with the multipart boundary");
    }
    readHeaders(raw);
    return new Body(raw, ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII));
  }

  private static void readHeaders(InputStream raw) throws IOException {
    String name = null;
    int total = 0;
    while (true) {
      StringBuilder line = new StringBuilder();
      int b;
      while ((b = raw.read()) != '\r') {
        if (b < 0 || b == '\n' || ++total > MAX_HEADER_BYTES) {
          throw malformed("the part headers are malformed or too long");
        }
        line.append((char) b);
      }
      if (raw.read() != '\n') {
        throw malformed("part header lines must end with CRLF");
      }
      total += 2;
      if (line.isEmpty()) {
        break;
      }
      int colon = line.indexOf(":");
      if (colon <= 0) {
        throw malformed("a part header has no name");
      }
      String header = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      if (header.equals("content-disposition")) {
        Matcher m = NAME.matcher(line.substring(colon + 1).trim());
        name = m.matches() ? m.group(1) : null;
      }
    }
    if (!FIELD.equals(name)) {
      throw malformed("expected one form-data part named \"" + FIELD + "\"");
    }
  }

  static ArtifactRejectedException malformed(String why) {
    return new ArtifactRejectedException(ProblemType.VALIDATION_FAILED, why);
  }

  /** The part's bytes; reports end of stream only after the closing boundary has been checked. */
  private static final class Body extends InputStream {

    private final InputStream raw;
    private final byte[] delimiter;
    private final byte[] buffer;
    private int pos;
    private int limit;
    private boolean eof;
    private boolean done;

    Body(InputStream raw, byte[] delimiter) {
      this.raw = raw;
      this.delimiter = delimiter;
      this.buffer = new byte[WINDOW + delimiter.length];
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] out, int off, int len) throws IOException {
      if (done) {
        return -1;
      }
      if (len == 0) {
        return 0;
      }
      while (true) {
        int at = indexOfDelimiter();
        if (at == pos) {
          pos += delimiter.length;
          checkClose();
          done = true;
          return -1;
        }
        int safe;
        if (at >= 0) {
          safe = at - pos;
        } else if (eof) {
          throw malformed("the body ends without its closing boundary");
        } else {
          // Keep the last delimiter.length - 1 bytes: they could be the start of a delimiter.
          safe = limit - pos - (delimiter.length - 1);
        }
        if (safe > 0) {
          int n = Math.min(len, safe);
          System.arraycopy(buffer, pos, out, off, n);
          pos += n;
          return n;
        }
        fill();
      }
    }

    /** After the delimiter: {@code --} closes the body; {@code CRLF} would start a second part. */
    private void checkClose() throws IOException {
      while (limit - pos < 2 && !eof) {
        fill();
      }
      if (limit - pos < 2 || buffer[pos] != '-' || buffer[pos + 1] != '-') {
        throw malformed("exactly one part is allowed, closed by the final boundary");
      }
    }

    private int indexOfDelimiter() {
      outer:
      for (int i = pos; i <= limit - delimiter.length; i++) {
        for (int j = 0; j < delimiter.length; j++) {
          if (buffer[i + j] != delimiter[j]) {
            continue outer;
          }
        }
        return i;
      }
      return -1;
    }

    private void fill() throws IOException {
      if (pos > 0) {
        System.arraycopy(buffer, pos, buffer, 0, limit - pos);
        limit -= pos;
        pos = 0;
      }
      int n = raw.read(buffer, limit, buffer.length - limit);
      if (n < 0) {
        eof = true;
      } else {
        limit += n;
      }
    }
  }
}

package dev.testgrid.api.artifact;

import dev.testgrid.api.problem.ProblemType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Checks an upload against its kind while it streams (PRD-06 FR-3), so a violation stops the copy
 * at the first bad byte instead of after the whole body has been stored:
 *
 * <ul>
 *   <li>more than {@link ArtifactKind#maxBytes()} bytes: {@code 413 artifact-too-large};
 *   <li>a screenshot not starting with the PNG signature, a trace not starting with a ZIP local
 *       file header, a log that isn't valid UTF-8 or contains NUL, or an empty upload: {@code 415
 *       unsupported-media-type}.
 * </ul>
 *
 * No bytes are handed on before the signature has been checked.
 */
public final class ArtifactValidatingStream extends InputStream {

  static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
  static final byte[] ZIP = {'P', 'K', 3, 4};

  private final InputStream in;
  private final ArtifactKind kind;
  private final CharsetDecoder utf8;
  private final CharBuffer sink = CharBuffer.allocate(8 * 1024);
  private ByteBuffer pending = ByteBuffer.allocate(0);
  private byte[] prefix;
  private int prefixPos;
  private long count;
  private boolean finished;

  public ArtifactValidatingStream(InputStream in, ArtifactKind kind) {
    this.in = in;
    this.kind = kind;
    this.utf8 =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    int n = read(one, 0, 1);
    return n < 0 ? -1 : one[0] & 0xff;
  }

  @Override
  public int read(byte[] out, int off, int len) throws IOException {
    if (len == 0) {
      return 0;
    }
    if (prefix == null) {
      byte[] signature = signature();
      // A log has no signature: read one byte so an empty log is refused like an empty image.
      prefix = in.readNBytes(signature == null ? 1 : signature.length);
      if (prefix.length == 0) {
        throw unsupported("the upload is empty");
      }
      if (signature != null && !Arrays.equals(prefix, signature)) {
        throw unsupported(
            "the bytes are not a " + kind.extension().toUpperCase(Locale.ROOT) + " file");
      }
      inspect(prefix, 0, prefix.length);
    }
    int n;
    if (prefixPos < prefix.length) {
      n = Math.min(len, prefix.length - prefixPos);
      System.arraycopy(prefix, prefixPos, out, off, n);
      prefixPos += n;
      return n;
    }
    n = in.read(out, off, len);
    if (n < 0) {
      finish();
      return -1;
    }
    inspect(out, off, n);
    return n;
  }

  private byte[] signature() {
    return switch (kind) {
      case SCREENSHOT -> PNG;
      case TRACE -> ZIP;
      case LOG -> null;
    };
  }

  private void inspect(byte[] bytes, int off, int n) throws IOException {
    count += n;
    if (count > kind.maxBytes()) {
      throw new ArtifactRejectedException(
          ProblemType.ARTIFACT_TOO_LARGE,
          "a " + kind + " artifact is at most " + kind.maxBytes() + " bytes");
    }
    if (kind == ArtifactKind.LOG) {
      decode(bytes, off, n, false);
    }
  }

  /** Streaming UTF-8 check: an incomplete sequence at a chunk edge is carried into the next one. */
  private void decode(byte[] bytes, int off, int n, boolean last) throws IOException {
    for (int i = off; i < off + n; i++) {
      if (bytes[i] == 0) {
        throw unsupported("a log must not contain NUL bytes");
      }
    }
    ByteBuffer input = ByteBuffer.allocate(pending.remaining() + n);
    input.put(pending).put(bytes, off, n).flip();
    while (true) {
      sink.clear();
      CoderResult result = utf8.decode(input, sink, last);
      if (result.isError()) {
        throw unsupported("a log must be valid UTF-8 text");
      }
      if (result.isUnderflow()) {
        break;
      }
    }
    pending = input.slice();
    if (last) {
      sink.clear();
      if (utf8.flush(sink).isError() || pending.hasRemaining()) {
        throw unsupported("a log must be valid UTF-8 text");
      }
    }
  }

  private void finish() throws IOException {
    if (finished) {
      return;
    }
    finished = true;
    if (kind == ArtifactKind.LOG) {
      decode(new byte[0], 0, 0, true);
    }
  }

  private static ArtifactRejectedException unsupported(String why) {
    return new ArtifactRejectedException(ProblemType.UNSUPPORTED_MEDIA_TYPE, why);
  }
}

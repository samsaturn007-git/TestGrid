package dev.testgrid.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.problem.ProblemType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PRD-06 FR-3 / T2: the streaming single-part multipart reader. */
class SingleFilePartTest {

  static final String BOUNDARY = "----tg-boundary-7Mq2";

  static byte[] body(String boundary, String headers, byte[] content, String tail) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(("--" + boundary + "\r\n" + headers + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.writeBytes(content);
    out.writeBytes(("\r\n--" + boundary + tail).getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  static byte[] fileBody(byte[] content) {
    return body(
        BOUNDARY,
        "Content-Disposition: form-data; name=\"file\"; filename=\"blob\"\r\n"
            + "Content-Type: application/octet-stream\r\n",
        content,
        "--\r\n");
  }

  /** Hands out 1..maxChunk bytes per read, so delimiters land across read boundaries. */
  static InputStream chunked(byte[] bytes, long seed, int maxChunk) {
    Random random = new Random(seed);
    return new ByteArrayInputStream(bytes) {
      @Override
      public synchronized int read(byte[] b, int off, int len) {
        return super.read(b, off, Math.min(len, 1 + random.nextInt(maxChunk)));
      }
    };
  }

  static byte[] readPart(InputStream raw) throws IOException {
    try (InputStream part = SingleFilePart.open(raw, BOUNDARY)) {
      return part.readAllBytes();
    }
  }

  @Test
  void parsesTheBoundaryOfAFormDataContentType() {
    assertThat(SingleFilePart.boundary("multipart/form-data; boundary=" + BOUNDARY))
        .isEqualTo(BOUNDARY);
    assertThat(SingleFilePart.boundary("multipart/form-data; boundary=\"a b'c\""))
        .isEqualTo("a b'c");
    assertThat(SingleFilePart.boundary("Multipart/Form-Data; boundary=x")).isEqualTo("x");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/json",
        "multipart/mixed; boundary=x",
        "multipart/form-data",
        "multipart/form-data; boundary=",
        "multipart/form-data; boundary=\"ends in space \"",
        "multipart/form-data; boundary=bad\\char",
        "not a mime type"
      })
  void refusesAnythingElse(String contentType) {
    assertThat(SingleFilePart.boundary(contentType)).isNull();
  }

  @Test
  void refusesABoundaryLongerThan70Characters() {
    assertThat(SingleFilePart.boundary("multipart/form-data; boundary=" + "a".repeat(70)))
        .isNotNull();
    assertThat(SingleFilePart.boundary("multipart/form-data; boundary=" + "a".repeat(71))).isNull();
  }

  @Test
  void streamsRandomBinaryContentExactlyWhateverTheReadSizes() throws Exception {
    Random random = new Random(20261123);
    // Sizes around the 64 KB window, plus content full of near-misses of the delimiter.
    int[] sizes = {0, 1, 2, 75, 65_535, 65_536, 65_537, 200_000};
    for (int size : sizes) {
      byte[] content = new byte[size];
      random.nextBytes(content);
      String nearMiss = "\r\n--" + BOUNDARY.substring(0, BOUNDARY.length() - 1) + "X";
      byte[] miss = nearMiss.getBytes(StandardCharsets.US_ASCII);
      for (int at = 0; at + miss.length < size; at += 9_973) {
        System.arraycopy(miss, 0, content, at, miss.length);
      }
      for (int maxChunk : new int[] {1, 7, 4096, 100_000}) {
        assertThat(readPart(chunked(fileBody(content), size + maxChunk, maxChunk)))
            .as("size %d, chunks up to %d", size, maxChunk)
            .isEqualTo(content);
      }
    }
  }

  @Test
  void ignoresTheEpilogueAfterTheClosingBoundary() throws Exception {
    byte[] raw =
        body(
            BOUNDARY,
            "content-disposition: form-data; name=\"file\"\r\n",
            "abc".getBytes(StandardCharsets.UTF_8),
            "--\r\nepilogue text");
    assertThat(readPart(new ByteArrayInputStream(raw))).isEqualTo("abc".getBytes());
  }

  @Test
  void aFilenameOfFileIsNotTheFieldName() {
    byte[] raw =
        body(
            BOUNDARY,
            "Content-Disposition: form-data; name=\"other\"; filename=\"file\"\r\n",
            new byte[] {1},
            "--");
    assertMalformed(raw);
  }

  @Test
  void rejectsAPreamble() {
    byte[] content = fileBody(new byte[] {1, 2});
    byte[] withPreamble = new byte[content.length + 2];
    withPreamble[0] = '\r';
    withPreamble[1] = '\n';
    System.arraycopy(content, 0, withPreamble, 2, content.length);
    assertMalformed(withPreamble);
  }

  @Test
  void rejectsAMissingOrWrongContentDisposition() {
    assertMalformed(body(BOUNDARY, "Content-Type: image/png\r\n", new byte[] {1}, "--"));
    assertMalformed(
        body(BOUNDARY, "Content-Disposition: attachment; name=\"file\"\r\n", new byte[] {1}, "--"));
  }

  @Test
  void rejectsASecondPart() {
    byte[] raw =
        body(
            BOUNDARY,
            "Content-Disposition: form-data; name=\"file\"\r\n",
            new byte[] {1},
            "\r\nContent-Disposition: form-data; name=\"extra\"\r\n\r\nx\r\n--" + BOUNDARY + "--");
    assertMalformed(raw);
  }

  @Test
  void rejectsABodyCutOffBeforeItsClosingBoundary() {
    byte[] whole = fileBody(new byte[1000]);
    byte[] cut = java.util.Arrays.copyOf(whole, whole.length - BOUNDARY.length() - 6);
    assertMalformed(cut);
    byte[] noDashes =
        body(BOUNDARY, "Content-Disposition: form-data; name=\"file\"\r\n", new byte[] {1}, "");
    assertMalformed(noDashes);
  }

  @Test
  void rejectsOverlongOrBareLfHeaders() {
    assertMalformed(
        body(
            BOUNDARY,
            "Content-Disposition: form-data; name=\"file\"\r\nX-Pad: "
                + "a".repeat(SingleFilePart.MAX_HEADER_BYTES)
                + "\r\n",
            new byte[] {1},
            "--"));
    byte[] bareLf =
        ("--"
                + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"file\"\n\n\u0001\r\n--"
                + BOUNDARY
                + "--")
            .getBytes(StandardCharsets.UTF_8);
    assertMalformed(bareLf);
  }

  private static void assertMalformed(byte[] raw) {
    assertThatThrownBy(() -> readPart(new ByteArrayInputStream(raw)))
        .isInstanceOfSatisfying(
            ArtifactRejectedException.class,
            e -> assertThat(e.type()).isEqualTo(ProblemType.VALIDATION_FAILED));
  }
}

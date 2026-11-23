package dev.testgrid.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.problem.ProblemType;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** PRD-06 FR-3 / T2, ART-001: per-kind size limits and magic-byte sniffing while streaming. */
class ArtifactValidatingStreamTest {

  static byte[] png(int size) {
    byte[] bytes = Arrays.copyOf(ArtifactValidatingStream.PNG, size);
    Arrays.fill(bytes, ArtifactValidatingStream.PNG.length, size, (byte) 7);
    return bytes;
  }

  static byte[] zip(int size) {
    byte[] bytes = Arrays.copyOf(ArtifactValidatingStream.ZIP, size);
    Arrays.fill(bytes, ArtifactValidatingStream.ZIP.length, size, (byte) 1);
    return bytes;
  }

  /** One byte per read, so a multi-byte UTF-8 character is always split. */
  static InputStream trickle(byte[] bytes) {
    return new ByteArrayInputStream(bytes) {
      @Override
      public synchronized int read(byte[] b, int off, int len) {
        return super.read(b, off, Math.min(len, 1));
      }
    };
  }

  static byte[] drain(byte[] bytes, ArtifactKind kind) throws Exception {
    return new ArtifactValidatingStream(trickle(bytes), kind).readAllBytes();
  }

  static void assertRejected(byte[] bytes, ArtifactKind kind, ProblemType type) {
    assertThatThrownBy(() ->
            new ArtifactValidatingStream(new ByteArrayInputStream(bytes), kind).readAllBytes())
        .isInstanceOfSatisfying(
            ArtifactRejectedException.class, e -> assertThat(e.type()).isEqualTo(type));
  }

  @Test
  void passesValidFilesThroughUnchanged() throws Exception {
    assertThat(drain(png(100), ArtifactKind.SCREENSHOT)).isEqualTo(png(100));
    assertThat(drain(zip(100), ArtifactKind.TRACE)).isEqualTo(zip(100));
    byte[] log = "naïve ✓ 日本 🙂 log\nline two\ttab".getBytes(StandardCharsets.UTF_8);
    assertThat(drain(log, ArtifactKind.LOG)).isEqualTo(log);
  }

  @Test
  void acceptsExactlyTheLimit() throws Exception {
    assertThat(drain(png((int) ArtifactKind.SCREENSHOT.maxBytes()), ArtifactKind.SCREENSHOT))
        .hasSize((int) ArtifactKind.SCREENSHOT.maxBytes());
  }

  @Test
  void art001RefusesOneByteOverTheLimitForEachKind() {
    assertRejected(
        png((int) ArtifactKind.SCREENSHOT.maxBytes() + 1),
        ArtifactKind.SCREENSHOT,
        ProblemType.ARTIFACT_TOO_LARGE);
    assertRejected(
        zip((int) ArtifactKind.TRACE.maxBytes() + 1),
        ArtifactKind.TRACE,
        ProblemType.ARTIFACT_TOO_LARGE);
    byte[] log = new byte[(int) ArtifactKind.LOG.maxBytes() + 1];
    Arrays.fill(log, (byte) 'a');
    assertRejected(log, ArtifactKind.LOG, ProblemType.ARTIFACT_TOO_LARGE);
  }

  @Test
  void art001RefusesTheWrongBytesForTheKind() {
    assertRejected(zip(100), ArtifactKind.SCREENSHOT, ProblemType.UNSUPPORTED_MEDIA_TYPE);
    assertRejected(png(100), ArtifactKind.TRACE, ProblemType.UNSUPPORTED_MEDIA_TYPE);
    assertRejected(
        "<html>".getBytes(), ArtifactKind.SCREENSHOT, ProblemType.UNSUPPORTED_MEDIA_TYPE);
    // Shorter than the signature.
    assertRejected(
        new byte[] {(byte) 0x89, 'P'}, ArtifactKind.SCREENSHOT, ProblemType.UNSUPPORTED_MEDIA_TYPE);
  }

  @Test
  void art001RefusesEmptyUploads() {
    for (ArtifactKind kind : ArtifactKind.values()) {
      assertRejected(new byte[0], kind, ProblemType.UNSUPPORTED_MEDIA_TYPE);
    }
  }

  @Test
  void art001RefusesLogsThatAreNotUtf8Text() {
    assertRejected(png(40), ArtifactKind.LOG, ProblemType.UNSUPPORTED_MEDIA_TYPE);
    assertRejected(
        new byte[] {'o', 'k', (byte) 0xff}, ArtifactKind.LOG, ProblemType.UNSUPPORTED_MEDIA_TYPE);
    assertRejected(
        "a\u0000b".getBytes(StandardCharsets.UTF_8),
        ArtifactKind.LOG,
        ProblemType.UNSUPPORTED_MEDIA_TYPE);
    // A multi-byte character cut off at the end of the upload.
    byte[] cut = Arrays.copyOf("ok ✓".getBytes(StandardCharsets.UTF_8), 5);
    assertThatThrownBy(() -> drain(cut, ArtifactKind.LOG))
        .isInstanceOfSatisfying(
            ArtifactRejectedException.class,
            e -> assertThat(e.type()).isEqualTo(ProblemType.UNSUPPORTED_MEDIA_TYPE));
  }

  @Test
  void aRejectedUploadLeavesNoFileInTheStore(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
    LocalFsArtifactStore store = new LocalFsArtifactStore(root);
    byte[] tooBig = png((int) ArtifactKind.SCREENSHOT.maxBytes() + 10);

    assertThatThrownBy(() ->
            store.put(
                new ArtifactValidatingStream(
                    new ByteArrayInputStream(tooBig), ArtifactKind.SCREENSHOT),
                new ArtifactStore.Meta(ArtifactKind.SCREENSHOT, java.time.Instant.now())))
        .isInstanceOf(ArtifactRejectedException.class);
    try (var walk = java.nio.file.Files.walk(root)) {
      assertThat(walk.filter(java.nio.file.Files::isRegularFile)).isEmpty();
    }
  }
}

package dev.testgrid.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PRD-06 FR-2 / T1. ART-003: traversal, symlinks and forged keys never leave the root. */
class LocalFsArtifactStoreTest {

  private static final Instant AT = Instant.parse("2026-11-23T10:15:30Z");

  @TempDir Path tmp;
  Path root;
  Path outside;
  LocalFsArtifactStore store;

  @BeforeEach
  void setUp() throws IOException {
    root = tmp.resolve("root");
    outside = Files.createDirectory(tmp.resolve("outside"));
    store = new LocalFsArtifactStore(root);
  }

  private static ArtifactStore.Meta meta(ArtifactKind kind) {
    return new ArtifactStore.Meta(kind, AT);
  }

  private static InputStream bytes(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void putStoresUnderAServerKeyWithSizeAndSha256() throws Exception {
    ArtifactStore.Stored stored = store.put(bytes("hello trace"), meta(ArtifactKind.TRACE));

    assertThat(stored.key()).matches("2026/11/23/[0-9a-f-]{36}\\.zip");
    assertThat(ArtifactKeys.isValid(stored.key())).isTrue();
    assertThat(stored.byteSize()).isEqualTo(11);
    String expected =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("hello trace".getBytes(StandardCharsets.UTF_8)));
    assertThat(stored.sha256()).isEqualTo(expected);
    try (InputStream in = store.open(stored.key())) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello trace");
    }
    assertThat(store.exists(stored.key())).isTrue();
    assertThat(store.keys()).containsExactly(stored.key());
  }

  @Test
  void keyExtensionFollowsTheKind() throws Exception {
    assertThat(store.put(bytes("a"), meta(ArtifactKind.SCREENSHOT)).key()).endsWith(".png");
    assertThat(store.put(bytes("b"), meta(ArtifactKind.LOG)).key()).endsWith(".log");
  }

  @Test
  void deleteRemovesTheObjectOnce() throws Exception {
    String key = store.put(bytes("x"), meta(ArtifactKind.LOG)).key();

    assertThat(store.delete(key)).isTrue();
    assertThat(store.delete(key)).isFalse();
    assertThat(store.exists(key)).isFalse();
    assertThatThrownBy(() -> store.open(key))
        .isInstanceOf(NoSuchFileException.class)
        .hasMessage(key);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../outside/secret.log",
        "2026/11/23/../../../../outside/x.log",
        "/etc/passwd",
        "2026\\11\\23\\0f8fad5b-d9cb-469f-a165-70867728950e.log",
        "2026/11/23/0f8fad5b-d9cb-469f-a165-70867728950e.log\u0000.png",
        "2026/11/23/0F8FAD5B-D9CB-469F-A165-70867728950E.log",
        "2026/11/23/0f8fad5b-d9cb-469f-a165-70867728950e.sh",
        "2026/13/23/0f8fad5b-d9cb-469f-a165-70867728950e.log",
        ".incoming/0f8fad5b-d9cb-469f-a165-70867728950e.part",
        "0f8fad5b-d9cb-469f-a165-70867728950e.log",
        ""
      })
  void art003RejectsAnyKeyItCouldNotHaveGenerated(String key) {
    assertThatThrownBy(() -> store.open(key)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.exists(key)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.delete(key)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void art003NullKeyIsRejected() {
    assertThatThrownBy(() -> store.open(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void art003RefusesASymlinkedFile() throws Exception {
    Path secret = Files.writeString(outside.resolve("secret.txt"), "do not read");
    String key = "2026/11/23/0f8fad5b-d9cb-469f-a165-70867728950e.log";
    Files.createDirectories(root.resolve("2026/11/23"));
    Files.createSymbolicLink(root.resolve(key), secret);

    assertThatThrownBy(() -> store.open(key)).isInstanceOf(UnsafeArtifactPathException.class);
    assertThatThrownBy(() -> store.exists(key)).isInstanceOf(UnsafeArtifactPathException.class);
    assertThatThrownBy(() -> store.delete(key)).isInstanceOf(UnsafeArtifactPathException.class);
    assertThat(secret).hasContent("do not read");
    assertThat(store.keys()).isEmpty();
  }

  @Test
  void art003RefusesASymlinkedDirectoryOnReadAndWrite() throws Exception {
    Files.writeString(outside.resolve("0f8fad5b-d9cb-469f-a165-70867728950e.log"), "outside");
    Files.createDirectories(root.resolve("2026/11"));
    Files.createSymbolicLink(root.resolve("2026/11/23"), outside);

    assertThatThrownBy(() -> store.open("2026/11/23/0f8fad5b-d9cb-469f-a165-70867728950e.log"))
        .isInstanceOf(UnsafeArtifactPathException.class);
    // A put dated that day must not write through the link either.
    assertThatThrownBy(() -> store.put(bytes("new"), meta(ArtifactKind.LOG)))
        .isInstanceOf(UnsafeArtifactPathException.class);
    try (Stream<Path> files = Files.list(outside)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .containsExactly("0f8fad5b-d9cb-469f-a165-70867728950e.log");
    }
    assertThat(store.keys()).isEmpty();
  }

  @Test
  void art003RefusesAFileWhereADirectoryBelongs() throws Exception {
    Files.createDirectories(root.resolve("2026/11"));
    Files.writeString(root.resolve("2026/11/23"), "not a directory");

    assertThatThrownBy(() -> store.put(bytes("x"), meta(ArtifactKind.LOG)))
        .isInstanceOf(UnsafeArtifactPathException.class);
  }

  @Test
  void art001AFailingStreamLeavesNoFileBehind() throws Exception {
    InputStream failsMidway =
        new InputStream() {
          private int sent;

          @Override
          public int read() throws IOException {
            if (sent++ < 100_000) {
              return 'a';
            }
            throw new IOException("upload cut off");
          }
        };

    assertThatThrownBy(() -> store.put(failsMidway, meta(ArtifactKind.TRACE)))
        .isInstanceOf(IOException.class)
        .hasMessage("upload cut off");
    assertThat(regularFiles()).isEmpty();
  }

  @Test
  void art001AValidatorRuntimeExceptionAlsoLeavesNoFile() throws Exception {
    InputStream rejects =
        new InputStream() {
          @Override
          public int read() {
            throw new IllegalStateException("wrong magic bytes");
          }
        };

    assertThatThrownBy(() -> store.put(rejects, meta(ArtifactKind.SCREENSHOT)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(regularFiles()).isEmpty();
  }

  @Test
  void keysSkipsPartialUploadsLinksAndStrayFiles() throws Exception {
    String key = store.put(bytes("kept"), meta(ArtifactKind.LOG)).key();
    Files.writeString(root.resolve(".incoming/0f8fad5b-d9cb-469f-a165-70867728950e.part"), "x");
    Files.writeString(root.resolve("2026/11/23/notes.txt"), "stray");
    Files.createSymbolicLink(
        root.resolve("2026/11/23/1f8fad5b-d9cb-469f-a165-70867728950e.log"),
        Files.writeString(outside.resolve("t.log"), "x"));

    assertThat(store.keys()).containsExactly(key);
  }

  @Test
  void aSymlinkToTheRootItselfIsAllowed() throws Exception {
    Path link = Files.createSymbolicLink(tmp.resolve("link-to-root"), root);
    LocalFsArtifactStore viaLink = new LocalFsArtifactStore(link);
    String key = viaLink.put(bytes("ok"), meta(ArtifactKind.LOG)).key();

    assertThat(viaLink.root()).isEqualTo(root.toRealPath());
    assertThat(store.exists(key)).isTrue();
  }

  private List<Path> regularFiles() throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile).toList();
    }
  }
}

package dev.testgrid.api.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Artifact bytes on a local volume (PRD-06 FR-2, ADR-005).
 *
 * <p>Path defence, in order: the key must match {@link ArtifactKeys#PATTERN}; the resolved path
 * must stay under the real root; no component between the root and the file may be a symbolic link;
 * files are opened with {@code NOFOLLOW_LINKS}. Only the API writes under the root, so the
 * check-then-use gap between the link check and the open would need someone who can already write
 * there; {@code NOFOLLOW_LINKS} still refuses a link swapped in for the file itself.
 *
 * <p>{@link #put} writes {@code .incoming/<uuid>.part} on the same filesystem and makes it visible
 * with an atomic rename, so a reader never sees half a file and a failed upload leaves nothing.
 */
public final class LocalFsArtifactStore implements ArtifactStore {

  static final String INCOMING = ".incoming";
  private static final int BUFFER = 64 * 1024;

  private final Path root;
  private final Path incoming;

  public LocalFsArtifactStore(Path configuredRoot) throws IOException {
    Files.createDirectories(configuredRoot);
    // The operator chooses the root, so a link *to* the root is allowed; nothing below it is.
    this.root = configuredRoot.toRealPath();
    this.incoming = root.resolve(INCOMING);
    ensureDirectory(incoming);
  }

  public Path root() {
    return root;
  }

  @Override
  public Stored put(InputStream in, Meta meta) throws IOException {
    String key = ArtifactKeys.newKey(meta.kind(), meta.createdAt());
    Path target = resolve(key);
    ensureDirectory(target.getParent());
    Path part = incoming.resolve(UUID.randomUUID() + ".part");
    MessageDigest sha256 = sha256();
    long size = 0;
    boolean moved = false;
    try {
      try (FileChannel out =
          FileChannel.open(
              part,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[BUFFER];
        int n;
        while ((n = in.read(buffer)) != -1) {
          sha256.update(buffer, 0, n);
          ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, n);
          while (chunk.hasRemaining()) {
            out.write(chunk);
          }
          size += n;
        }
        out.force(true);
      }
      refuseLinks(target);
      Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
      moved = true;
      return new Stored(key, size, HexFormat.of().formatHex(sha256.digest()));
    } finally {
      if (!moved) {
        Files.deleteIfExists(part);
      }
    }
  }

  @Override
  public InputStream open(String key) throws IOException {
    Path file = resolve(key);
    refuseLinks(file);
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      throw new NoSuchFileException(key);
    }
    if (!attributes.isRegularFile()) {
      throw new UnsafeArtifactPathException("artifact " + key + " is not a regular file");
    }
    return Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public boolean delete(String key) throws IOException {
    Path file = resolve(key);
    refuseLinks(file);
    return Files.deleteIfExists(file);
  }

  @Override
  public boolean exists(String key) throws IOException {
    Path file = resolve(key);
    refuseLinks(file);
    return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public List<String> keys() throws IOException {
    // Files.walk does not follow links, and a link entry is not a regular file under NOFOLLOW.
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(p -> !p.startsWith(incoming))
          .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
          .map(p -> root.relativize(p).toString().replace(p.getFileSystem().getSeparator(), "/"))
          .filter(ArtifactKeys::isValid)
          .sorted()
          .toList();
    }
  }

  private Path resolve(String key) throws UnsafeArtifactPathException {
    Path file = root.resolve(ArtifactKeys.require(key)).normalize();
    if (!file.startsWith(root) || file.equals(root)) {
      throw new UnsafeArtifactPathException("artifact key escapes the store root");
    }
    return file;
  }

  /** Every existing component from the root down to {@code path} must not be a link. */
  private void refuseLinks(Path path) throws UnsafeArtifactPathException {
    Path current = root;
    for (Path name : root.relativize(path)) {
      current = current.resolve(name);
      if (Files.isSymbolicLink(current)) {
        throw new UnsafeArtifactPathException("refused a symbolic link under the artifact root");
      }
    }
  }

  /** Creates the directories one level at a time, refusing any existing link or non-directory. */
  private void ensureDirectory(Path dir) throws IOException {
    Path current = root;
    for (Path name : root.relativize(dir)) {
      current = current.resolve(name);
      try {
        Files.createDirectory(current);
      } catch (FileAlreadyExistsException e) {
        BasicFileAttributes attributes =
            Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
          throw new UnsafeArtifactPathException(
              "refused a link or file where an artifact directory belongs");
        }
      }
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java platform must provide SHA-256", e);
    }
  }
}

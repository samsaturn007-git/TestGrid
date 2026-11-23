package dev.testgrid.api.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * Where artifact bytes live (PRD-06 FR-2, ADR-005). The {@code artifacts} table is the source of
 * truth; a store only keeps bytes under keys it generated itself. Callers never pass a path.
 *
 * <p>Every method that takes a key rejects one that doesn't match {@link ArtifactKeys#PATTERN} with
 * {@link IllegalArgumentException}. A key that is valid but absent is {@link
 * java.nio.file.NoSuchFileException} from {@link #open}.
 */
public interface ArtifactStore {

  /** What to store: the kind picks the extension, and the time picks the key's date. */
  record Meta(ArtifactKind kind, Instant createdAt) {}

  /** The stored object: its key, its exact size and the lowercase hex sha256 of its bytes. */
  record Stored(String key, long byteSize, String sha256) {}

  /**
   * Streams {@code in} into a new object. Either the whole object becomes visible under the
   * returned key, or nothing is left behind: any exception from {@code in} (including a size or
   * type violation thrown by a validating wrapper) removes the partial bytes before it propagates.
   */
  Stored put(InputStream in, Meta meta) throws IOException;

  InputStream open(String key) throws IOException;

  /** True if an object was removed; false if there was none. */
  boolean delete(String key) throws IOException;

  boolean exists(String key) throws IOException;

  /**
   * Every stored key, for the orphan sweeper (FR-6). Entries that aren't valid keys are skipped.
   */
  List<String> keys() throws IOException;
}

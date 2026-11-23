package dev.testgrid.api.artifact;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * PRD-06 FR-6. Two passes, both safe to repeat and to interrupt at any point:
 *
 * <ul>
 *   <li>{@link #purgeOnce}: AVAILABLE rows past {@code expires_at} become EXPIRED; then for every
 *       EXPIRED row the bytes are deleted and only then is the row marked PURGED. A crash between
 *       the two leaves an EXPIRED row, which the next pass finishes; a delete that fails leaves the
 *       row EXPIRED (reads already answer {@code 410}) and is retried next pass.
 *   <li>{@link #sweepOnce}: files with no row are deleted once older than the grace period (an
 *       upload's file exists briefly before its row); AVAILABLE rows whose file is gone become
 *       MISSING (reads answer {@code 404}); partial uploads older than the grace are removed.
 * </ul>
 *
 * Every age compares with the database clock, never the API's.
 */
@Service
public class ArtifactRetention {

  private static final Logger log = LoggerFactory.getLogger(ArtifactRetention.class);
  // PostgreSQL orders uuid as unsigned bytes, so the all-zero UUID sorts first. (Java's signed
  // UUID order would put 8000… first and skip every id starting 0–7: found by a test.)
  private static final UUID BEFORE_ALL = new UUID(0L, 0L);

  public record PurgeResult(int expired, int purged, int failed) {}

  public record SweepResult(int orphanFilesDeleted, int rowsMarkedMissing, int partsDeleted) {}

  private final ArtifactRepository artifacts;
  private final ArtifactStore store;
  private final ArtifactProperties properties;
  private final JdbcClient jdbc;

  public ArtifactRetention(
      ArtifactRepository artifacts,
      ArtifactStore store,
      ArtifactProperties properties,
      JdbcClient jdbc) {
    this.artifacts = artifacts;
    this.store = store;
    this.properties = properties;
    this.jdbc = jdbc;
  }

  public PurgeResult purgeOnce() {
    int batch = properties.purgeBatchSize();
    int expired = 0;
    List<ArtifactRepository.KeyedRow> rows;
    do {
      rows = artifacts.expireDue(batch);
      expired += rows.size();
    } while (rows.size() == batch);

    int purged = 0;
    int failed = 0;
    // Rows whose delete failed stay EXPIRED; remember them so this pass doesn't loop on them.
    Set<UUID> skip = new HashSet<>();
    while (true) {
      List<ArtifactRepository.KeyedRow> todo =
          artifacts.expired(batch + skip.size()).stream()
              .filter(r -> !skip.contains(r.id()))
              .toList();
      if (todo.isEmpty()) {
        break;
      }
      for (ArtifactRepository.KeyedRow row : todo) {
        try {
          store.delete(row.storageKey());
          if (artifacts.markPurged(row.id())) {
            purged++;
          }
        } catch (IOException | RuntimeException e) {
          failed++;
          skip.add(row.id());
          log.warn(
              "could not delete expired artifact {}; retrying next pass: {}",
              row.id(),
              e.toString());
        }
      }
    }
    if (expired + purged + failed > 0) {
      log.info("artifact purge expired={} purged={} failed={}", expired, purged, failed);
    }
    return new PurgeResult(expired, purged, failed);
  }

  public SweepResult sweepOnce() throws IOException {
    Instant cutoff = dbNow().minus(properties.orphanGrace());
    int batch = properties.purgeBatchSize();

    int orphans = 0;
    List<ArtifactStore.Entry> old =
        store.entries().stream().filter(e -> e.lastModified().isBefore(cutoff)).toList();
    for (int i = 0; i < old.size(); i += batch) {
      List<String> keys = new ArrayList<>();
      old.subList(i, Math.min(old.size(), i + batch)).forEach(e -> keys.add(e.key()));
      Set<String> known = artifacts.knownKeys(keys);
      for (String key : keys) {
        if (!known.contains(key) && store.delete(key)) {
          orphans++;
          log.warn("deleted an artifact file with no database row: {}", key);
        }
      }
    }

    int missing = 0;
    UUID after = BEFORE_ALL;
    List<ArtifactRepository.KeyedRow> rows;
    do {
      rows = artifacts.availableAfter(after, batch);
      for (ArtifactRepository.KeyedRow row : rows) {
        after = row.id();
        if (!store.exists(row.storageKey()) && artifacts.markMissing(row.id())) {
          missing++;
          log.warn("artifact {} has no bytes in the store; marked MISSING", row.id());
        }
      }
    } while (rows.size() == batch);

    int parts = store.deleteStaleParts(cutoff);
    if (orphans + missing + parts > 0) {
      log.info(
          "artifact sweep orphanFiles={} missingRows={} staleParts={}", orphans, missing, parts);
    }
    return new SweepResult(orphans, missing, parts);
  }

  private Instant dbNow() {
    return jdbc.sql("SELECT now()").query(Timestamp.class).single().toInstant();
  }
}

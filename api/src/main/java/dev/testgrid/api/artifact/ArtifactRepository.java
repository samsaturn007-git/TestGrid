package dev.testgrid.api.artifact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code artifacts} table (V8). Plain SQL, like the rest of the API. */
@Repository
public class ArtifactRepository {

  private static final String COLUMNS =
      "id, attempt_id, case_key, kind, mime, storage_key, byte_size, sha256, state, expires_at,"
          + " created_at";

  private final JdbcClient jdbc;

  public ArtifactRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A row together with its storage key, which never leaves the server. */
  public record Stored(ArtifactView view, String storageKey) {}

  public record Usage(int count, long bytes) {}

  /** What an attempt already holds; the caller has locked the attempt row. */
  public Usage usage(UUID attemptId) {
    return jdbc.sql(
            "SELECT count(*) AS n, coalesce(sum(byte_size), 0) AS bytes FROM artifacts"
                + " WHERE attempt_id = :a")
        .param("a", attemptId)
        .query((rs, i) -> new Usage(rs.getInt("n"), rs.getLong("bytes")))
        .single();
  }

  /** {@code expires_at} comes from the database clock, like every other deadline. */
  public Stored insert(
      UUID attemptId,
      String caseKey,
      ArtifactKind kind,
      ArtifactStore.Stored object,
      Duration retention) {
    return jdbc.sql(
            "INSERT INTO artifacts (attempt_id, case_key, kind, mime, storage_key, byte_size,"
                + " sha256, expires_at)"
                + " VALUES (:a, :c, :k, :m, :key, :size, :sha, now() + make_interval(secs => :ttl))"
                + " RETURNING "
                + COLUMNS)
        .param("a", attemptId)
        .param("c", caseKey)
        .param("k", kind.name())
        .param("m", kind.mime())
        .param("key", object.key())
        .param("size", object.byteSize())
        .param("sha", object.sha256())
        .param("ttl", (double) retention.toSeconds())
        .query(ArtifactRepository::map)
        .single();
  }

  /**
   * One artifact with what retrieval decides on, all from one statement: the owning project (for
   * the caller scope), the database clock, and whether {@code expires_at} has passed even if the
   * purge job hasn't marked the row yet.
   */
  public record Found(Stored artifact, UUID projectId, Instant dbNow, boolean pastExpiry) {}

  public Optional<Found> find(UUID artifactId) {
    return jdbc.sql(
            "SELECT a.id, a.attempt_id, a.case_key, a.kind, a.mime, a.storage_key, a.byte_size,"
                + " a.sha256, a.state, a.expires_at, a.created_at, r.project_id, now() AS db_now,"
                + " a.expires_at <= now() AS past_expiry"
                + " FROM artifacts a"
                + " JOIN attempts t ON t.id = a.attempt_id"
                + " JOIN run_targets g ON g.id = t.target_id"
                + " JOIN runs r ON r.id = g.run_id"
                + " WHERE a.id = :id")
        .param("id", artifactId)
        .query((rs, i) ->
            new Found(
                map(rs, i),
                rs.getObject("project_id", UUID.class),
                rs.getObject("db_now", OffsetDateTime.class).toInstant(),
                rs.getBoolean("past_expiry")))
        .optional();
  }

  /**
   * Every artifact of a run's attempts, oldest first, with the effective state a reader sees (past
   * {@code expires_at} reads EXPIRED). Served by {@code artifacts(attempt_id)}.
   */
  public Map<UUID, List<ArtifactView>> byAttemptForRun(UUID runId) {
    Map<UUID, List<ArtifactView>> out = new HashMap<>();
    jdbc.sql(
            "SELECT a.id, a.attempt_id, a.case_key, a.kind, a.mime, a.storage_key, a.byte_size,"
                + " a.sha256, a.state, a.expires_at, a.created_at, a.expires_at <= now() AS past"
                + " FROM artifacts a"
                + " JOIN attempts t ON t.id = a.attempt_id"
                + " JOIN run_targets g ON g.id = t.target_id"
                + " WHERE g.run_id = :r"
                + " ORDER BY a.attempt_id, a.created_at, a.id")
        .param("r", runId)
        .query(rs -> {
          ArtifactView view = map(rs, 0).view().effective(rs.getBoolean("past"));
          out.computeIfAbsent(view.attemptId(), k -> new ArrayList<>()).add(view);
        });
    return out;
  }

  // PRD-06 FR-6 retention. Each statement handles at most `batch` rows; SKIP LOCKED keeps two
  // passes (or a second API instance) from waiting on each other.

  public record KeyedRow(UUID id, String storageKey) {}

  /**
   * AVAILABLE rows past {@code expires_at} (database clock) become EXPIRED; served by {@code
   * artifacts_expiring}.
   */
  public List<KeyedRow> expireDue(int batch) {
    return jdbc.sql(
            """
            UPDATE artifacts SET state = 'EXPIRED', version = version + 1
            WHERE id IN (SELECT id FROM artifacts
                         WHERE state = 'AVAILABLE' AND expires_at <= now()
                         ORDER BY expires_at LIMIT :batch FOR UPDATE SKIP LOCKED)
            RETURNING id, storage_key
            """)
        .param("batch", batch)
        .query((rs, i) -> new KeyedRow(rs.getObject("id", UUID.class), rs.getString("storage_key")))
        .list();
  }

  /** EXPIRED rows whose bytes still have to go (this pass's, and any an earlier pass failed on). */
  public List<KeyedRow> expired(int batch) {
    return jdbc.sql(
            "SELECT id, storage_key FROM artifacts WHERE state = 'EXPIRED' ORDER BY id LIMIT :batch")
        .param("batch", batch)
        .query((rs, i) -> new KeyedRow(rs.getObject("id", UUID.class), rs.getString("storage_key")))
        .list();
  }

  /** Only after the bytes are deleted; conditional, so a row is purged once. */
  public boolean markPurged(UUID id) {
    return jdbc.sql(
                "UPDATE artifacts SET state = 'PURGED', version = version + 1"
                    + " WHERE id = :id AND state = 'EXPIRED'")
            .param("id", id)
            .update()
        == 1;
  }

  /** AVAILABLE rows after {@code afterId}, by id (keyset), for the missing-bytes check. */
  public List<KeyedRow> availableAfter(UUID afterId, int batch) {
    return jdbc.sql(
            "SELECT id, storage_key FROM artifacts WHERE state = 'AVAILABLE' AND id > :after"
                + " ORDER BY id LIMIT :batch")
        .param("after", afterId)
        .param("batch", batch)
        .query((rs, i) -> new KeyedRow(rs.getObject("id", UUID.class), rs.getString("storage_key")))
        .list();
  }

  public boolean markMissing(UUID id) {
    return jdbc.sql(
                "UPDATE artifacts SET state = 'MISSING', version = version + 1"
                    + " WHERE id = :id AND state IN ('AVAILABLE', 'EXPIRED')")
            .param("id", id)
            .update()
        == 1;
  }

  /** Which of these keys have a row (in any state). */
  public Set<String> knownKeys(List<String> keys) {
    if (keys.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        jdbc.sql("SELECT storage_key FROM artifacts WHERE storage_key IN (:keys)")
            .param("keys", keys)
            .query(String.class)
            .list());
  }

  /**
   * Called in the completion transaction (CONFLICT_REGISTER N20): each artifact uploaded for a case
   * points at the case result just written for it.
   */
  public int linkToCaseResults(UUID attemptId) {
    return jdbc.sql(
            """
            UPDATE artifacts a SET case_result_id = c.id
            FROM case_results c
            WHERE a.attempt_id = :a AND c.attempt_id = :a AND c.case_key = a.case_key
              AND a.case_result_id IS NULL
            """)
        .param("a", attemptId)
        .update();
  }

  static Stored map(ResultSet rs, int row) throws SQLException {
    return new Stored(
        new ArtifactView(
            rs.getObject("id", UUID.class),
            rs.getObject("attempt_id", UUID.class),
            rs.getString("case_key"),
            ArtifactKind.valueOf(rs.getString("kind")),
            rs.getString("mime"),
            rs.getLong("byte_size"),
            rs.getString("sha256"),
            ArtifactState.valueOf(rs.getString("state")),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()),
        rs.getString("storage_key"));
  }
}

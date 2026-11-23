package dev.testgrid.api.artifact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
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

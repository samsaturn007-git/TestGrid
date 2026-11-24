package dev.testgrid.api.quality;

import dev.testgrid.api.quality.QualityPolicyView.VersionSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@code quality_policies} (contracts §3): immutable versions, at most one active per project. */
@Repository
public class QualityPolicyRepository {

  public record StoredPolicy(
      UUID id,
      UUID projectId,
      int version,
      boolean active,
      Instant createdAt,
      QualityPolicy policy) {}

  private static final String COLUMNS =
      "id, project_id, version, active, created_at, policy::text AS policy";

  private final JdbcClient jdbc;
  private final JsonMapper mapper;

  public QualityPolicyRepository(JdbcClient jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Optional<StoredPolicy> active(UUID projectId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM quality_policies WHERE project_id = :p AND active")
        .param("p", projectId)
        .query(this::map)
        .optional();
  }

  public Optional<StoredPolicy> version(UUID projectId, int version) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM quality_policies WHERE project_id = :p AND version = :v")
        .param("p", projectId)
        .param("v", version)
        .query(this::map)
        .optional();
  }

  public StoredPolicy byId(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM quality_policies WHERE id = :id")
        .param("id", id)
        .query(this::map)
        .single();
  }

  public List<VersionSummary> versions(UUID projectId, int limit) {
    return jdbc.sql(
            """
            SELECT id, version, active, created_at FROM quality_policies
            WHERE project_id = :p ORDER BY version DESC LIMIT :limit
            """)
        .param("p", projectId)
        .param("limit", limit)
        .query((rs, i) ->
            new VersionSummary(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /**
   * Deactivates the current version and inserts the next one as active, in the caller's
   * transaction. A transaction-scoped advisory lock per project serialises concurrent PUTs, so two
   * of them can't both read the same "next" version; locking the project row instead would block
   * run creation, whose foreign key takes a share lock on it. Returns the previous version (0 if
   * none) and the new row.
   */
  public Replaced replaceActive(UUID projectId, QualityPolicy policy) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('quality_policies:' || :p, 0))")
        .param("p", projectId.toString())
        .query()
        .singleRow();
    int previous =
        jdbc.sql(
                """
                UPDATE quality_policies SET active = false
                WHERE project_id = :p AND active
                RETURNING version
                """)
            .param("p", projectId)
            .query(Integer.class)
            .optional()
            .orElse(0);
    UUID id =
        jdbc.sql(
                """
                INSERT INTO quality_policies (project_id, version, policy, active)
                SELECT :p, coalesce(max(version), 0) + 1, CAST(:policy AS jsonb), true
                FROM quality_policies WHERE project_id = :p
                RETURNING id
                """)
            .param("p", projectId)
            .param("policy", mapper.writeValueAsString(policy))
            .query(UUID.class)
            .single();
    return new Replaced(previous, byId(id));
  }

  public record Replaced(int previousVersion, StoredPolicy current) {}

  private StoredPolicy map(ResultSet rs, int row) throws SQLException {
    return new StoredPolicy(
        rs.getObject("id", UUID.class),
        rs.getObject("project_id", UUID.class),
        rs.getInt("version"),
        rs.getBoolean("active"),
        rs.getTimestamp("created_at").toInstant(),
        mapper.readValue(rs.getString("policy"), QualityPolicy.class));
  }
}

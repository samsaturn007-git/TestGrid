package dev.testgrid.api.quarantine;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.quality.QualityRules.QuarantineFact;
import dev.testgrid.api.quarantine.QuarantineList.Suggestion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code quarantines} (contracts §3). "Active" is always {@code revoked_at IS NULL AND expires_at >
 * now()} on the database clock, in the query that needs it.
 */
@Repository
public class QuarantineRepository {

  private static final String VIEW_COLUMNS =
      """
      id, project_id, case_key, engine, reason, source, created_by, created_at, expires_at,
      revoked_at,
      CASE WHEN revoked_at IS NOT NULL THEN 'REVOKED'
           WHEN expires_at <= now() THEN 'EXPIRED'
           ELSE 'ACTIVE' END AS state
      """;

  private static final String ACTIVE = "revoked_at IS NULL AND expires_at > now()";

  private final JdbcClient jdbc;

  public QuarantineRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Serialises quarantine creation per project (manual, suggested and AUTO alike) for the rest of
   * the transaction, so the duplicate check and the {@code maxActive} cap can't both pass for two
   * requests at once.
   */
  public void lockProject(UUID projectId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('quarantines:' || :p, 0))")
        .param("p", projectId.toString())
        .query()
        .singleRow();
  }

  public boolean caseExists(UUID projectId, String caseKey) {
    return jdbc.sql(
            """
            SELECT EXISTS (
              SELECT 1 FROM test_cases tc JOIN suites s ON s.id = tc.suite_id
              WHERE s.project_id = :p AND s.is_current AND tc.case_key = :k)
            """)
        .param("p", projectId)
        .param("k", caseKey)
        .query(Boolean.class)
        .single();
  }

  /** True when {@code expiresAt} is after the database's now and at most 30 days after it. */
  public boolean expiryInRange(Instant expiresAt) {
    return jdbc.sql(
            "SELECT CAST(:e AS timestamptz) > now() AND CAST(:e AS timestamptz) <= now() + interval '30 days'")
        .param("e", Timestamp.from(expiresAt))
        .query(Boolean.class)
        .single();
  }

  /** An active quarantine that already covers this case on this engine (null: every engine). */
  public Optional<UUID> activeCovering(UUID projectId, String caseKey, Engine engine) {
    return jdbc.sql(
            "SELECT id FROM quarantines WHERE project_id = :p AND case_key = :k AND "
                + ACTIVE
                + " AND (engine IS NULL OR engine = :e) ORDER BY created_at LIMIT 1")
        .param("p", projectId)
        .param("k", caseKey)
        .param("e", engine == null ? null : engine.wireName())
        .query(UUID.class)
        .optional();
  }

  public int activeCount(UUID projectId) {
    return jdbc.sql("SELECT count(*) FROM quarantines WHERE project_id = :p AND " + ACTIVE)
        .param("p", projectId)
        .query(Integer.class)
        .single();
  }

  public UUID insert(
      UUID projectId,
      String caseKey,
      Engine engine,
      String reason,
      QuarantineSource source,
      String createdBy,
      Instant expiresAt) {
    return jdbc.sql(
            """
            INSERT INTO quarantines (project_id, case_key, engine, reason, source, created_by,
                                     expires_at)
            VALUES (:p, :k, :e, :r, :s, :by, :x)
            RETURNING id
            """)
        .param("p", projectId)
        .param("k", caseKey)
        .param("e", engine == null ? null : engine.wireName())
        .param("r", reason)
        .param("s", source.name())
        .param("by", createdBy)
        .param("x", Timestamp.from(expiresAt))
        .query(UUID.class)
        .single();
  }

  /** The database clock plus {@code days}, for AUTO quarantines. */
  public Instant nowPlusDays(int days) {
    return jdbc.sql("SELECT now() + make_interval(days => :d)")
        .param("d", days)
        .query(Timestamp.class)
        .single()
        .toInstant();
  }

  public Optional<QuarantineView> find(UUID projectId, UUID id, boolean forUpdate) {
    return jdbc.sql(
            "SELECT "
                + VIEW_COLUMNS
                + " FROM quarantines WHERE id = :id AND project_id = :p"
                + (forUpdate ? " FOR UPDATE" : ""))
        .param("id", id)
        .param("p", projectId)
        .query(QuarantineRepository::view)
        .optional();
  }

  /** Revokes an active quarantine; false when it was already revoked or expired. */
  public boolean revoke(UUID id) {
    return jdbc.sql(
                "UPDATE quarantines SET revoked_at = now(), version = version + 1 WHERE id = :id AND "
                    + ACTIVE)
            .param("id", id)
            .update()
        == 1;
  }

  /** Newest first. {@code state} null lists all. */
  public List<QuarantineView> list(UUID projectId, QuarantineState state, int limit) {
    String filter =
        state == null
            ? ""
            : switch (state) {
              case ACTIVE -> " AND " + ACTIVE;
              case EXPIRED -> " AND revoked_at IS NULL AND expires_at <= now()";
              case REVOKED -> " AND revoked_at IS NOT NULL";
            };
    return jdbc.sql(
            "SELECT "
                + VIEW_COLUMNS
                + " FROM quarantines WHERE project_id = :p"
                + filter
                + " ORDER BY created_at DESC, id DESC LIMIT :limit")
        .param("p", projectId)
        .param("limit", limit)
        .query(QuarantineRepository::view)
        .list();
  }

  /** What the evaluator needs: the project's active quarantines at this transaction's now(). */
  public List<QuarantineFact> activeFacts(UUID projectId) {
    return jdbc.sql(
            "SELECT id, case_key, engine, expires_at FROM quarantines WHERE project_id = :p AND "
                + ACTIVE
                + " ORDER BY created_at, id")
        .param("p", projectId)
        .query((rs, i) -> {
          String engine = rs.getString("engine");
          return new QuarantineFact(
              rs.getObject("id", UUID.class),
              rs.getString("case_key"),
              engine == null ? null : Engine.fromWireName(engine),
              rs.getTimestamp("expires_at").toInstant());
        })
        .list();
  }

  public record Expired(
      UUID id, UUID projectId, String caseKey, String engine, Instant expiresAt) {}

  /**
   * Marks up to {@code limit} quarantines whose expiry has passed and not yet been recorded. SKIP
   * LOCKED lets two API instances share the work without recording one expiry twice.
   */
  public List<Expired> recordExpiries(int limit) {
    return jdbc.sql(
            """
            UPDATE quarantines SET expiry_recorded_at = now(), version = version + 1
            WHERE id IN (
              SELECT id FROM quarantines
              WHERE revoked_at IS NULL AND expiry_recorded_at IS NULL AND expires_at <= now()
              ORDER BY expires_at
              LIMIT :limit
              FOR UPDATE SKIP LOCKED)
            RETURNING id, project_id, case_key, engine, expires_at
            """)
        .param("limit", limit)
        .query((rs, i) ->
            new Expired(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("case_key"),
                rs.getString("engine"),
                rs.getTimestamp("expires_at").toInstant()))
        .list();
  }

  /** A FLAKY case-health row that AUTO may quarantine. */
  public record FlakyCase(String caseKey, Engine engine, int observed, String reason) {}

  /** The run's (case, engine) pairs labelled FLAKY with at least {@code minObservations}. */
  public List<FlakyCase> flakyCasesOfRun(
      UUID projectId, String suiteKey, UUID runId, int minObservations) {
    return jdbc.sql(
            """
            SELECT DISTINCT h.case_key, h.engine, h.observed, h.reason
            FROM case_health h
            JOIN run_targets t ON t.run_id = :r AND t.engine = h.engine
            JOIN attempts a ON a.target_id = t.id
            JOIN case_results cr ON cr.attempt_id = a.id AND cr.case_key = h.case_key
            WHERE h.project_id = :p AND h.suite_key = :s AND h.label = 'FLAKY'
              AND h.observed >= :n
            ORDER BY h.case_key, h.engine
            """)
        .param("r", runId)
        .param("p", projectId)
        .param("s", suiteKey)
        .param("n", minObservations)
        .query((rs, i) ->
            new FlakyCase(
                rs.getString("case_key"),
                Engine.fromWireName(rs.getString("engine")),
                rs.getInt("observed"),
                rs.getString("reason")))
        .list();
  }

  /** FLAKY case-health rows with no active quarantine covering them, most flips first. */
  public List<Suggestion> suggestions(UUID projectId, int limit) {
    return jdbc.sql(
            """
            SELECT h.suite_key, h.case_key, h.engine, h.observed, h.flaky_count, h.flip_rate,
                   h.reason
            FROM case_health h
            WHERE h.project_id = :p AND h.label = 'FLAKY'
              AND NOT EXISTS (
                SELECT 1 FROM quarantines q
                WHERE q.project_id = h.project_id AND q.case_key = h.case_key
                  AND (q.engine IS NULL OR q.engine = h.engine)
                  AND q.revoked_at IS NULL AND q.expires_at > now())
            ORDER BY h.flip_rate DESC, h.suite_key, h.case_key, h.engine
            LIMIT :limit
            """)
        .param("p", projectId)
        .param("limit", limit)
        .query((rs, i) ->
            new Suggestion(
                rs.getString("suite_key"),
                rs.getString("case_key"),
                Engine.fromWireName(rs.getString("engine")),
                rs.getInt("observed"),
                rs.getInt("flaky_count"),
                rs.getDouble("flip_rate"),
                rs.getString("reason")))
        .list();
  }

  public Instant now() {
    return jdbc.sql("SELECT now()").query(Timestamp.class).single().toInstant();
  }

  private static QuarantineView view(ResultSet rs, int row) throws SQLException {
    String engine = rs.getString("engine");
    Timestamp revoked = rs.getTimestamp("revoked_at");
    return new QuarantineView(
        rs.getObject("id", UUID.class),
        rs.getObject("project_id", UUID.class),
        rs.getString("case_key"),
        engine == null ? null : Engine.fromWireName(engine),
        rs.getString("reason"),
        QuarantineSource.valueOf(rs.getString("source")),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant(),
        revoked == null ? null : revoked.toInstant(),
        QuarantineState.valueOf(rs.getString("state")));
  }
}

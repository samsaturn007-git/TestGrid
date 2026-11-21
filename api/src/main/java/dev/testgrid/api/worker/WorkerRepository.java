package dev.testgrid.api.worker;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.Executor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the worker registry (PRD-04 FR-7, contracts §3 {@code workers}). */
@Repository
public class WorkerRepository {

  private final JdbcClient jdbc;

  public WorkerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Advisory-lock class for worker names (the second key is {@code hashtext(name)}). */
  static final int NAME_LOCK_CLASS = 0x7467; // "tg"

  /**
   * Serialises registrations of one name until commit, so two concurrent registrations (a retry
   * after a timeout, say) can't both see no live row and then collide on the unique index.
   */
  public void lockName(String name) {
    jdbc.sql("SELECT pg_advisory_xact_lock(:c, hashtext(:n))")
        .param("c", NAME_LOCK_CLASS)
        .param("n", name)
        .query()
        .singleRow();
  }

  /**
   * A container that registers again under its name (it restarted) replaces its old row: the old
   * process is gone, so the old row is LOST. The partial unique index {@code
   * workers_one_live_per_name} allows only one live row per name.
   */
  public int markPreviousLost(String name) {
    return jdbc.sql("UPDATE workers SET state = 'LOST' WHERE name = :n AND state <> 'LOST'")
        .param("n", name)
        .update();
  }

  public WorkerView insert(
      String name, String version, Executor executor, List<Engine> engines, int slots) {
    UUID id =
        jdbc.sql(
                """
                INSERT INTO workers (name, version, executor, engines, slots)
                VALUES (:n, :v, :e, CAST(:engines AS text[]), :s)
                RETURNING id
                """)
            .param("n", name)
            .param("v", version)
            .param("e", executor.name())
            .param(
                "engines",
                "{" + String.join(",", engines.stream().map(Engine::wireName).toList()) + "}")
            .param("s", slots)
            .query(UUID.class)
            .single();
    return find(id).orElseThrow();
  }

  /** Live rows only: a LOST row is never revived (its name may already have a new row). */
  public Optional<String> touch(UUID id) {
    return jdbc.sql(
            """
            UPDATE workers SET last_seen_at = now()
            WHERE id = :id AND state <> 'LOST'
            RETURNING state
            """)
        .param("id", id)
        .query(String.class)
        .optional();
  }

  public Optional<String> drain(UUID id) {
    return jdbc.sql(
            """
            UPDATE workers SET state = 'DRAINING', last_seen_at = now()
            WHERE id = :id AND state <> 'LOST'
            RETURNING state
            """)
        .param("id", id)
        .query(String.class)
        .optional();
  }

  public Optional<String> state(UUID id) {
    return jdbc.sql("SELECT state FROM workers WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .optional();
  }

  /** Live workers not heard from for {@code silenceSeconds} become LOST; returns their names. */
  public List<String> markSilentLost(int silenceSeconds) {
    return jdbc.sql(
            """
            UPDATE workers SET state = 'LOST'
            WHERE state <> 'LOST' AND last_seen_at < now() - make_interval(secs => :s)
            RETURNING name
            """)
        .param("s", silenceSeconds)
        .query(String.class)
        .list();
  }

  static final String COLUMNS =
      """
      w.id, w.name, w.version, w.executor, w.engines, w.slots, w.state, w.started_at,
      w.last_seen_at,
      CASE WHEN w.state = 'LOST' THEN 0 ELSE
        (SELECT count(*) FROM attempts a
         WHERE a.worker_id = w.name AND a.status IN ('CLAIMED', 'RUNNING'))::int
      END AS active_attempts
      """;

  public Optional<WorkerView> find(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM workers w WHERE w.id = :id")
        .param("id", id)
        .query((rs, i) -> map(rs))
        .optional();
  }

  /** Live workers, plus those lost in the last hour, live first. */
  public List<WorkerView> list(int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                FROM workers w
                WHERE w.state <> 'LOST' OR w.last_seen_at > now() - interval '1 hour'
                ORDER BY CASE w.state WHEN 'ACTIVE' THEN 0 WHEN 'DRAINING' THEN 1 ELSE 2 END,
                         w.name, w.started_at DESC
                LIMIT :limit
                """)
        .param("limit", limit)
        .query((rs, i) -> map(rs))
        .list();
  }

  private static WorkerView map(ResultSet rs) throws SQLException {
    String[] engines = (String[]) rs.getArray("engines").getArray();
    return new WorkerView(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("version"),
        Executor.valueOf(rs.getString("executor")),
        Arrays.stream(engines).map(Engine::fromWireName).toList(),
        rs.getInt("slots"),
        WorkerState.valueOf(rs.getString("state")),
        instant(rs, "started_at"),
        instant(rs, "last_seen_at"),
        rs.getInt("active_attempts"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getTimestamp(column).toInstant();
  }
}

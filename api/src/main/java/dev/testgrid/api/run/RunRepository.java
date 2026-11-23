package dev.testgrid.api.run;

import dev.testgrid.api.artifact.ArtifactView;
import dev.testgrid.api.domain.AttemptStatus;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.FixtureMode;
import dev.testgrid.api.domain.RunAggregate;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.ShardingMode;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.domain.Trigger;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for runs, targets and events. Every query takes the project ID (PRD-02 FR-10). */
@Repository
public class RunRepository {

  private final JdbcClient jdbc;

  public RunRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record CurrentSuite(
      UUID id, String revision, boolean enabled, Set<Engine> engines, Set<FixtureMode> modes) {}

  public record NewRun(
      UUID projectId,
      UUID suiteId,
      Trigger trigger,
      CreateRunRequest.Fixture fixture,
      CreateRunRequest.Source source,
      String idempotencyKey,
      byte[] requestHash,
      CreateRunRequest.Sharding sharding,
      UUID parentRunId) {}

  public boolean projectExists(UUID projectId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM projects WHERE id = :p)")
        .param("p", projectId)
        .query(Boolean.class)
        .single();
  }

  public Optional<CurrentSuite> findCurrentSuite(UUID projectId, String suiteKey) {
    return jdbc.sql(
            """
            SELECT id, revision, enabled, allowed_engines, fixture_modes
            FROM suites
            WHERE project_id = :p AND suite_key = :k AND is_current
            """)
        .param("p", projectId)
        .param("k", suiteKey)
        .query((rs, i) ->
            new CurrentSuite(
                rs.getObject("id", UUID.class),
                rs.getString("revision"),
                rs.getBoolean("enabled"),
                textArray(rs.getArray("allowed_engines")).stream()
                    .map(Engine::fromWireName)
                    .collect(Collectors.toUnmodifiableSet()),
                textArray(rs.getArray("fixture_modes")).stream()
                    .map(FixtureMode::valueOf)
                    .collect(Collectors.toUnmodifiableSet())))
        .optional();
  }

  public record ExistingRun(UUID id, byte[] requestHash) {}

  public Optional<ExistingRun> findByIdempotencyKey(UUID projectId, String idempotencyKey) {
    return jdbc.sql(
            "SELECT id, request_hash FROM runs WHERE project_id = :p AND idempotency_key = :key")
        .param("p", projectId)
        .param("key", idempotencyKey)
        .query(
            (rs, i) -> new ExistingRun(rs.getObject("id", UUID.class), rs.getBytes("request_hash")))
        .optional();
  }

  /**
   * Inserts the run unless the project already has one with this idempotency key. Under READ
   * COMMITTED, if another transaction holds an uncommitted row with the same key, this statement
   * waits for it: on commit it does nothing (empty result), on rollback it inserts. The unique
   * constraint, not application code, decides the winner (contracts §4, API-004).
   */
  public Optional<UUID> insertRunIfAbsent(NewRun run) {
    CreateRunRequest.Fixture fixture = run.fixture();
    CreateRunRequest.Source source = run.source();
    return jdbc.sql(
            """
            INSERT INTO runs (project_id, suite_id, status, trigger, fixture_mode, fixture_seed,
                              commit_sha, branch, ci_ref, idempotency_key, request_hash,
                              sharding_mode, shard_count, parent_run_id)
            VALUES (:p, :suite, 'QUEUED', :trigger, :mode, :seed, :sha, :branch, :ciRef,
                    :key, :hash, :sharding, :shards, :parent)
            ON CONFLICT (project_id, idempotency_key) DO NOTHING
            RETURNING id
            """)
        .param("p", run.projectId())
        .param("suite", run.suiteId())
        .param("trigger", run.trigger().name())
        .param("mode", fixture == null ? null : fixture.mode().name())
        .param("seed", fixture == null ? null : fixture.seed())
        .param("sha", source == null ? null : source.commitSha())
        .param("branch", source == null ? null : source.branch())
        .param("ciRef", source == null ? null : source.ciRef())
        .param("key", run.idempotencyKey())
        .param("hash", run.requestHash())
        .param("sharding", run.sharding().mode().name())
        .param("shards", run.sharding().shards())
        .param("parent", run.parentRunId())
        .query(UUID.class)
        .optional();
  }

  public void insertTarget(
      UUID runId, String targetKey, Executor executor, Engine engine, int maxAttempts) {
    jdbc.sql(
            """
            INSERT INTO run_targets (run_id, target_key, executor, engine, status, max_attempts)
            VALUES (:run, :key, :executor, :engine, 'QUEUED', :maxAttempts)
            """)
        .param("run", runId)
        .param("key", targetKey)
        .param("executor", executor.name())
        .param("engine", engine.wireName())
        .param("maxAttempts", maxAttempts)
        .update();
  }

  public void insertRunEvent(UUID runId, EventType type, String payloadJson) {
    jdbc.sql(
            "INSERT INTO run_events (run_id, type, payload) VALUES (:run, :type, CAST(:payload AS"
                + " jsonb))")
        .param("run", runId)
        .param("type", type.name())
        .param("payload", payloadJson)
        .update();
  }

  public Optional<RunResponse> findRun(UUID projectId, UUID runId) {
    // Reruns of this run, oldest first (PRD-05 FR-6: the link is shown in both directions).
    List<UUID> rerunIds =
        jdbc.sql(
                """
                SELECT id FROM runs
                WHERE project_id = :p AND parent_run_id = :id
                ORDER BY queued_at, id
                """)
            .param("p", projectId)
            .param("id", runId)
            .query(UUID.class)
            .list();
    List<RunResponse.Target> targets =
        jdbc.sql(
                """
                SELECT t.*,\
                """
                    + ATTEMPT_COLUMNS
                    + """
                    FROM run_targets t JOIN runs r ON r.id = t.run_id
                    LEFT JOIN LATERAL (
                      SELECT * FROM attempts x WHERE x.target_id = t.id
                      ORDER BY x.attempt_no DESC LIMIT 1) a ON true
                    WHERE r.project_id = :p AND t.run_id = :id
                    ORDER BY t.target_key
                    """)
            .param("p", projectId)
            .param("id", runId)
            .query((rs, i) -> mapTarget(rs))
            .list();
    return jdbc.sql(
            """
            SELECT r.*, s.suite_key, s.revision
            FROM runs r JOIN suites s ON s.id = r.suite_id
            WHERE r.project_id = :p AND r.id = :id
            """)
        .param("p", projectId)
        .param("id", runId)
        .query((rs, i) -> mapRun(rs, targets, rerunIds))
        .optional();
  }

  // Keyset pagination (FR-6). The row comparison is an index condition on
  // runs(project_id, queued_at DESC, id DESC), so every page is an index range scan of at most
  // limit+1 rows: O(limit) per page however deep the client pages. id breaks queued_at ties.
  // The PRD-05 filters only add conditions; V5's indexes keep the same order after the status
  // and branch columns, so a filtered page is still a range scan. Every value is a bound
  // parameter: the SQL text depends only on which filters are present, never on their values.
  private static final String LIST_SELECT =
      """
      SELECT r.id, r.project_id, r.status, r.trigger, r.fixture_mode, r.fixture_seed,
             r.commit_sha, r.branch, r.ci_ref, r.queued_at, r.started_at, r.finished_at,
             s.suite_key, s.revision,
             (SELECT count(*) FROM run_targets t WHERE t.run_id = r.id) AS target_count
      FROM runs r JOIN suites s ON s.id = r.suite_id
      """;

  static String listSql(RunFilter filter, boolean afterCursor) {
    StringBuilder where = new StringBuilder("WHERE r.project_id = :p");
    if (!filter.statuses().isEmpty()) {
      where.append(" AND r.status IN (:statuses)");
    }
    if (filter.suiteKey() != null) {
      where.append(" AND s.suite_key = :suiteKey");
    }
    if (filter.branch() != null) {
      where.append(" AND r.branch = :branch");
    }
    if (!filter.triggers().isEmpty()) {
      where.append(" AND r.trigger IN (:triggers)");
    }
    if (afterCursor) {
      where.append(" AND (r.queued_at, r.id) < (:queuedAt, :id)");
    }
    return LIST_SELECT + where + "\nORDER BY r.queued_at DESC, r.id DESC\nLIMIT :limit\n";
  }

  static final String LIST_FIRST_PAGE = listSql(RunFilter.NONE, false);
  static final String LIST_AFTER_CURSOR = listSql(RunFilter.NONE, true);

  /** Up to {@code limit} matching runs after {@code cursor} (or from the newest), newest first. */
  public List<RunSummary> listRuns(UUID projectId, RunFilter filter, RunCursor cursor, int limit) {
    var query =
        jdbc.sql(listSql(filter, cursor != null)).param("p", projectId).param("limit", limit);
    if (!filter.statuses().isEmpty()) {
      query = query.param("statuses", filter.statuses().stream().map(Enum::name).toList());
    }
    if (filter.suiteKey() != null) {
      query = query.param("suiteKey", filter.suiteKey());
    }
    if (filter.branch() != null) {
      query = query.param("branch", filter.branch());
    }
    if (!filter.triggers().isEmpty()) {
      query = query.param("triggers", filter.triggers().stream().map(Enum::name).toList());
    }
    if (cursor != null) {
      query =
          query
              .param("queuedAt", OffsetDateTime.ofInstant(cursor.queuedAt(), ZoneOffset.UTC))
              .param("id", cursor.id());
    }
    return query.query((rs, i) -> mapSummary(rs)).list();
  }

  private static RunSummary mapSummary(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    return new RunSummary(
        id,
        rs.getObject("project_id", UUID.class),
        rs.getString("suite_key"),
        rs.getString("revision"),
        RunStatus.valueOf(rs.getString("status")),
        Trigger.valueOf(rs.getString("trigger")),
        fixture(rs),
        source(rs),
        rs.getInt("target_count"),
        instant(rs, "queued_at"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"),
        new RunResponse.Links(RunResponse.selfLink(id), null));
  }

  private static RunResponse mapRun(
      ResultSet rs, List<RunResponse.Target> targets, List<UUID> rerunIds) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    UUID suiteId = rs.getObject("suite_id", UUID.class);
    RunStatus status =
        targets.isEmpty()
            ? RunStatus.valueOf(rs.getString("status"))
            : RunAggregate.of(
                targets.stream()
                    .map(t -> new RunAggregate.TargetState(t.status(), t.attemptCount()))
                    .toList());
    return new RunResponse(
        id,
        rs.getObject("project_id", UUID.class),
        suiteId,
        rs.getString("suite_key"),
        rs.getString("revision"),
        status,
        Trigger.valueOf(rs.getString("trigger")),
        rs.getString("idempotency_key"),
        fixture(rs),
        new CreateRunRequest.Sharding(
            ShardingMode.valueOf(rs.getString("sharding_mode")), rs.getInt("shard_count")),
        source(rs),
        instant(rs, "queued_at"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"),
        instant(rs, "cancel_requested_at"),
        rs.getObject("parent_run_id", UUID.class),
        rerunIds,
        targets,
        new RunResponse.Links(RunResponse.selfLink(id), RunResponse.suiteLink(suiteId)));
  }

  private static CreateRunRequest.Fixture fixture(ResultSet rs) throws SQLException {
    String mode = rs.getString("fixture_mode");
    return mode == null
        ? null
        : new CreateRunRequest.Fixture(
            FixtureMode.valueOf(mode), rs.getObject("fixture_seed", Integer.class));
  }

  private static CreateRunRequest.Source source(ResultSet rs) throws SQLException {
    String sha = rs.getString("commit_sha");
    String branch = rs.getString("branch");
    String ciRef = rs.getString("ci_ref");
    return sha == null && branch == null && ciRef == null
        ? null
        : new CreateRunRequest.Source(sha, branch, ciRef);
  }

  private static RunResponse.Target mapTarget(ResultSet rs) throws SQLException {
    return new RunResponse.Target(
        rs.getObject("id", UUID.class),
        rs.getString("target_key"),
        Executor.valueOf(rs.getString("executor")),
        Engine.fromWireName(rs.getString("engine")),
        TargetStatus.valueOf(rs.getString("status")),
        rs.getInt("attempt_count"),
        rs.getInt("max_attempts"),
        instant(rs, "queued_at"),
        instant(rs, "finished_at"),
        rs.getObject("a_id") == null ? null : mapAttempt(rs));
  }

  /** Attempt columns with an {@code a_} prefix, so they can sit next to target columns. */
  static final String ATTEMPT_COLUMNS =
      """
      a.id AS a_id, a.attempt_no AS a_attempt_no, a.worker_id AS a_worker_id,
      a.status AS a_status, a.claimed_at AS a_claimed_at, a.started_at AS a_started_at,
      a.finished_at AS a_finished_at, a.leased_until AS a_leased_until,
      (a.status IN ('CLAIMED', 'RUNNING') AND a.leased_until < now()) AS a_lease_expired,
      a.last_heartbeat_at AS a_last_heartbeat_at, a.error_class AS a_error_class,
      a.error_detail_redacted AS a_error_detail
      """;

  static AttemptSummary mapAttempt(ResultSet rs) throws SQLException {
    String errorClass = rs.getString("a_error_class");
    return new AttemptSummary(
        rs.getObject("a_id", UUID.class),
        rs.getInt("a_attempt_no"),
        rs.getString("a_worker_id"),
        AttemptStatus.valueOf(rs.getString("a_status")),
        instant(rs, "a_claimed_at"),
        instant(rs, "a_started_at"),
        instant(rs, "a_finished_at"),
        instant(rs, "a_leased_until"),
        rs.getBoolean("a_lease_expired"),
        instant(rs, "a_last_heartbeat_at"),
        errorClass == null ? null : ErrorClass.valueOf(errorClass));
  }

  /** Every attempt of the run's targets, newest attempt first within each target. */
  public List<RunResults.Target> findResults(
      UUID runId, List<RunResponse.Target> targets, Map<UUID, List<ArtifactView>> artifacts) {
    record Row(UUID targetId, RunResults.Attempt attempt) {}
    List<Row> attempts =
        jdbc.sql(
                "SELECT a.target_id, "
                    + ATTEMPT_COLUMNS
                    + """
                    FROM attempts a JOIN run_targets t ON t.id = a.target_id
                    WHERE t.run_id = :run
                    ORDER BY a.target_id, a.attempt_no DESC
                    """)
            .param("run", runId)
            .query((rs, i) ->
                new Row(
                    rs.getObject("target_id", UUID.class),
                    new RunResults.Attempt(
                        mapAttempt(rs), rs.getString("a_error_detail"), List.of(), List.of())))
            .list();
    Map<UUID, List<RunResults.CaseResult>> cases = new HashMap<>();
    jdbc.sql(
            """
            SELECT c.* FROM case_results c
            JOIN attempts a ON a.id = c.attempt_id JOIN run_targets t ON t.id = a.target_id
            WHERE t.run_id = :run
            ORDER BY c.attempt_id, c.case_key
            """)
        .param("run", runId)
        .query(rs -> {
          String errorClass = rs.getString("error_class");
          cases
              .computeIfAbsent(rs.getObject("attempt_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new RunResults.CaseResult(
                      rs.getString("case_key"),
                      CaseStatus.valueOf(rs.getString("status")),
                      rs.getInt("duration_ms"),
                      rs.getInt("retry_count"),
                      errorClass == null ? null : ErrorClass.valueOf(errorClass),
                      rs.getString("error_message_redacted"),
                      rs.getObject("start_offset_ms", Integer.class)));
        });
    return targets.stream()
        .map(t ->
            new RunResults.Target(
                t.id(),
                t.targetKey(),
                t.engine(),
                t.status(),
                attempts.stream()
                    .filter(r -> r.targetId().equals(t.id()))
                    .map(r ->
                        new RunResults.Attempt(
                            r.attempt().attempt(),
                            r.attempt().errorDetail(),
                            List.copyOf(cases.getOrDefault(r.attempt().attempt().id(), List.of())),
                            artifacts.getOrDefault(r.attempt().attempt().id(), List.of())))
                    .toList()))
        .toList();
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }

  private static List<String> textArray(Array array) throws SQLException {
    return Arrays.asList((String[]) array.getArray());
  }
}

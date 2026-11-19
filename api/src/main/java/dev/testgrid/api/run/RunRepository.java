package dev.testgrid.api.run;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.FixtureMode;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.ShardingMode;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.domain.Trigger;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
      CreateRunRequest.Sharding sharding) {}

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

  public UUID insertRun(NewRun run) {
    CreateRunRequest.Fixture fixture = run.fixture();
    CreateRunRequest.Source source = run.source();
    return jdbc.sql(
            """
            INSERT INTO runs (project_id, suite_id, status, trigger, fixture_mode, fixture_seed,
                              commit_sha, branch, ci_ref, idempotency_key, request_hash,
                              sharding_mode, shard_count)
            VALUES (:p, :suite, 'QUEUED', :trigger, :mode, :seed, :sha, :branch, :ciRef,
                    :key, :hash, :sharding, :shards)
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
        .query(UUID.class)
        .single();
  }

  public void insertTarget(UUID runId, String targetKey, Executor executor, Engine engine) {
    jdbc.sql(
            """
            INSERT INTO run_targets (run_id, target_key, executor, engine, status)
            VALUES (:run, :key, :executor, :engine, 'QUEUED')
            """)
        .param("run", runId)
        .param("key", targetKey)
        .param("executor", executor.name())
        .param("engine", engine.wireName())
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
    Optional<RunResponse> run =
        jdbc.sql(
                """
                SELECT r.*, s.suite_key, s.revision
                FROM runs r JOIN suites s ON s.id = r.suite_id
                WHERE r.project_id = :p AND r.id = :id
                """)
            .param("p", projectId)
            .param("id", runId)
            .query((rs, i) -> mapRun(rs, List.of()))
            .optional();
    if (run.isEmpty()) {
      return run;
    }
    List<RunResponse.Target> targets =
        jdbc.sql(
                """
                SELECT t.* FROM run_targets t JOIN runs r ON r.id = t.run_id
                WHERE r.project_id = :p AND t.run_id = :id
                ORDER BY t.target_key
                """)
            .param("p", projectId)
            .param("id", runId)
            .query((rs, i) -> mapTarget(rs))
            .list();
    RunResponse r = run.get();
    return Optional.of(
        new RunResponse(
            r.id(),
            r.projectId(),
            r.suiteKey(),
            r.suiteRevision(),
            r.status(),
            r.trigger(),
            r.idempotencyKey(),
            r.fixture(),
            r.sharding(),
            r.source(),
            r.queuedAt(),
            r.startedAt(),
            r.finishedAt(),
            r.cancelRequestedAt(),
            targets,
            r.links()));
  }

  private static RunResponse mapRun(ResultSet rs, List<RunResponse.Target> targets)
      throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    String mode = rs.getString("fixture_mode");
    Integer seed = rs.getObject("fixture_seed", Integer.class);
    String sha = rs.getString("commit_sha");
    String branch = rs.getString("branch");
    String ciRef = rs.getString("ci_ref");
    return new RunResponse(
        id,
        rs.getObject("project_id", UUID.class),
        rs.getString("suite_key"),
        rs.getString("revision"),
        RunStatus.valueOf(rs.getString("status")),
        Trigger.valueOf(rs.getString("trigger")),
        rs.getString("idempotency_key"),
        mode == null ? null : new CreateRunRequest.Fixture(FixtureMode.valueOf(mode), seed),
        new CreateRunRequest.Sharding(
            ShardingMode.valueOf(rs.getString("sharding_mode")), rs.getInt("shard_count")),
        sha == null && branch == null && ciRef == null
            ? null
            : new CreateRunRequest.Source(sha, branch, ciRef),
        instant(rs, "queued_at"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"),
        instant(rs, "cancel_requested_at"),
        targets,
        new RunResponse.Links(RunResponse.selfLink(id)));
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
        instant(rs, "finished_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }

  private static List<String> textArray(Array array) throws SQLException {
    return Arrays.asList((String[]) array.getArray());
  }
}

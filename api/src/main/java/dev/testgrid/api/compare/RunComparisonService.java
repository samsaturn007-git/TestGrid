package dev.testgrid.api.compare;

import dev.testgrid.api.compare.RunComparison.CaseOutcome;
import dev.testgrid.api.compare.RunComparison.EngineSide;
import dev.testgrid.api.compare.RunComparison.Key;
import dev.testgrid.api.compare.RunComparison.Side;
import dev.testgrid.api.compare.RunComparisonView.CategoryCount;
import dev.testgrid.api.compare.RunComparisonView.ComparisonThresholds;
import dev.testgrid.api.compare.RunComparisonView.RunRef;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.FixtureMode;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Loads two runs and compares them (PRD-07 FR-6, contracts §8). */
@Service
public class RunComparisonService {

  private final JdbcClient jdbc;
  private final CallerScope scope;
  private final CompareProperties properties;

  public RunComparisonService(JdbcClient jdbc, CallerScope scope, CompareProperties properties) {
    this.jdbc = jdbc;
    this.scope = scope;
    this.properties = properties;
  }

  private record Loaded(RunRef ref, UUID suiteId) {}

  /**
   * {@code baseId} null means "last green": the newest PASSED run of the same suite key on the same
   * branch (both null counts as the same), queued before head. One snapshot for both runs.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public RunComparisonView compare(UUID baseId, UUID headId) {
    Loaded head = load(headId, "head");
    UUID resolved = baseId == null ? lastGreen(head.ref()) : baseId;
    Loaded base = load(resolved, "base");
    if (!base.ref().suiteKey().equals(head.ref().suiteKey())) {
      throw new ApiProblemException(
          ProblemType.INCOMPARABLE_RUNS,
          "base is a run of "
              + base.ref().suiteKey()
              + " and head a run of "
              + head.ref().suiteKey()
              + "; only runs of one suite key can be compared");
    }
    RunComparison.Result result =
        RunComparison.compare(
            side(base), side(head), health(head.ref().suiteKey()), properties.thresholds());
    List<CategoryCount> counts =
        result.counts().entrySet().stream()
            .map(e -> new CategoryCount(e.getKey(), e.getValue()))
            .toList();
    int regressions =
        (int) result.rows().stream().filter(RunComparison.Row::durationRegression).count();
    return new RunComparisonView(
        base.ref(),
        head.ref(),
        baseId == null,
        base.ref().revision().equals(head.ref().revision()),
        new ComparisonThresholds(properties.regressionRatio(), properties.regressionMinDeltaMs()),
        counts,
        regressions,
        result.rows());
  }

  private Loaded load(UUID runId, String which) {
    return jdbc.sql(
            """
            SELECT r.id, r.status, r.suite_id, s.suite_key, s.revision, r.branch, r.commit_sha,
                   r.fixture_mode, r.fixture_seed, r.trigger, r.queued_at, r.finished_at
            FROM runs r JOIN suites s ON s.id = r.suite_id
            WHERE r.id = :id AND r.project_id = :p
            """)
        .param("id", runId)
        .param("p", scope.projectId())
        .query((rs, i) -> {
          String mode = rs.getString("fixture_mode");
          Timestamp finished = rs.getTimestamp("finished_at");
          Integer seed = rs.getObject("fixture_seed", Integer.class);
          return new Loaded(
              new RunRef(
                  rs.getObject("id", UUID.class),
                  RunStatus.valueOf(rs.getString("status")),
                  rs.getString("suite_key"),
                  rs.getString("revision"),
                  rs.getString("branch"),
                  rs.getString("commit_sha"),
                  mode == null ? null : FixtureMode.valueOf(mode),
                  seed,
                  Trigger.valueOf(rs.getString("trigger")),
                  rs.getTimestamp("queued_at").toInstant(),
                  finished == null ? null : finished.toInstant()),
              rs.getObject("suite_id", UUID.class));
        })
        .optional()
        .orElseThrow(() ->
            new ApiProblemException(ProblemType.NOT_FOUND, which + " run " + runId + " not found"));
  }

  private UUID lastGreen(RunRef head) {
    return jdbc.sql(
            """
            SELECT r.id
            FROM runs r JOIN suites s ON s.id = r.suite_id
            WHERE r.project_id = :p AND s.suite_key = :k AND r.status = 'PASSED'
              AND r.branch IS NOT DISTINCT FROM :b
              AND (r.queued_at, r.id) < (:q, :h)
            ORDER BY r.queued_at DESC, r.id DESC
            LIMIT 1
            """)
        .param("p", scope.projectId())
        .param("k", head.suiteKey())
        .param("b", head.branch())
        .param("q", Timestamp.from(head.queuedAt()))
        .param("h", head.id())
        .query(UUID.class)
        .optional()
        .orElseThrow(() ->
            new ApiProblemException(
                ProblemType.NOT_FOUND,
                "no PASSED run of "
                    + head.suiteKey()
                    + (head.branch() == null ? " without a branch" : " on branch " + head.branch())
                    + " was queued before run "
                    + head.id()));
  }

  /**
   * The suite revision's cases, and per engine the case results of the newest attempt that has any,
   * for targets that finished with results. One target per engine until PRD-11's shards; with
   * shards, the shards' results merge into one engine.
   */
  private Side side(Loaded run) {
    Set<String> cases =
        new HashSet<>(
            jdbc.sql("SELECT case_key FROM test_cases WHERE suite_id = :s")
                .param("s", run.suiteId())
                .query(String.class)
                .list());
    Map<Engine, TargetStatus> statusOf = new EnumMap<>(Engine.class);
    jdbc.sql("SELECT engine, status FROM run_targets WHERE run_id = :r ORDER BY target_key")
        .param("r", run.ref().id())
        .query((rs, i) -> {
          statusOf.putIfAbsent(
              Engine.fromWireName(rs.getString("engine")),
              TargetStatus.valueOf(rs.getString("status")));
          return null;
        })
        .list();
    Map<Engine, Map<String, CaseOutcome>> results = new EnumMap<>(Engine.class);
    jdbc.sql(
            """
            SELECT t.engine, cr.case_key, cr.status, cr.duration_ms, cr.fingerprint_id
            FROM run_targets t
            JOIN LATERAL (
              SELECT a.id FROM attempts a
              WHERE a.target_id = t.id
                AND EXISTS (SELECT 1 FROM case_results x WHERE x.attempt_id = a.id)
              ORDER BY a.attempt_no DESC
              LIMIT 1
            ) latest ON true
            JOIN case_results cr ON cr.attempt_id = latest.id
            WHERE t.run_id = :r AND t.status IN ('PASSED', 'FAILED', 'TIMED_OUT')
            """)
        .param("r", run.ref().id())
        .query((rs, i) -> {
          results
              .computeIfAbsent(Engine.fromWireName(rs.getString("engine")), e -> new HashMap<>())
              .put(
                  rs.getString("case_key"),
                  new CaseOutcome(
                      CaseStatus.valueOf(rs.getString("status")),
                      rs.getInt("duration_ms"),
                      rs.getObject("fingerprint_id", UUID.class)));
          return null;
        })
        .list();
    Map<Engine, EngineSide> engines = new EnumMap<>(Engine.class);
    statusOf.forEach(
        (engine, status) -> engines.put(engine, new EngineSide(status, results.get(engine))));
    return new Side(cases, engines);
  }

  private Map<Key, String> health(String suiteKey) {
    Map<Key, String> labels = new HashMap<>();
    jdbc.sql(
            "SELECT case_key, engine, label FROM case_health WHERE project_id = :p AND suite_key = :k")
        .param("p", scope.projectId())
        .param("k", suiteKey)
        .query((rs, i) ->
            labels.put(
                new Key(rs.getString("case_key"), Engine.fromWireName(rs.getString("engine"))),
                rs.getString("label")))
        .list();
    return labels;
  }
}

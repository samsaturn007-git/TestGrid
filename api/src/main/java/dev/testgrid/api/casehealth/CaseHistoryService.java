package dev.testgrid.api.casehealth;

import dev.testgrid.api.casehealth.CaseHistoryView.EngineHistory;
import dev.testgrid.api.casehealth.CaseHistoryView.Health;
import dev.testgrid.api.casehealth.CaseHistoryView.Result;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** PRD-07 FR-5: a case's outcomes and durations per engine, and its health label. */
@Service
public class CaseHistoryService {

  private final JdbcClient jdbc;
  private final CallerScope scope;

  public CaseHistoryService(JdbcClient jdbc, CallerScope scope) {
    this.jdbc = jdbc;
    this.scope = scope;
  }

  private record Suite(String title, List<Engine> engines) {}

  /** One snapshot, so the label and the results it was computed from agree. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public CaseHistoryView history(String caseKey, String suiteKey, Engine engine, int limit) {
    UUID projectId = scope.projectId();
    Suite suite =
        suite(projectId, suiteKey, caseKey)
            .orElseThrow(() ->
                new ApiProblemException(
                    ProblemType.NOT_FOUND,
                    "case " + caseKey + " of suite " + suiteKey + " not found"));
    List<Engine> engines = engine == null ? suite.engines() : List.of(engine);
    List<EngineHistory> rows =
        engines.stream()
            .map(e ->
                new EngineHistory(
                    e,
                    health(projectId, suiteKey, caseKey, e).orElse(null),
                    results(projectId, suiteKey, caseKey, e, limit)))
            .toList();
    return new CaseHistoryView(caseKey, suiteKey, suite.title(), rows);
  }

  /**
   * The case's title and engines from the newest revision that has it (the current one if it still
   * does). Empty when no revision of this suite ever had the case.
   */
  private Optional<Suite> suite(UUID projectId, String suiteKey, String caseKey) {
    return jdbc.sql(
            """
            SELECT tc.title, s.allowed_engines
            FROM suites s JOIN test_cases tc ON tc.suite_id = s.id
            WHERE s.project_id = :p AND s.suite_key = :s AND tc.case_key = :c
            ORDER BY s.is_current DESC, s.created_at DESC
            LIMIT 1
            """)
        .param("p", projectId)
        .param("s", suiteKey)
        .param("c", caseKey)
        .query((rs, i) -> {
          Array engines = rs.getArray("allowed_engines");
          List<Engine> list =
              Arrays.stream((Object[]) engines.getArray())
                  .map(v -> Engine.fromWireName(String.valueOf(v)))
                  .sorted()
                  .toList();
          return new Suite(rs.getString("title"), list);
        })
        .optional();
  }

  private Optional<Health> health(UUID projectId, String suiteKey, String caseKey, Engine engine) {
    return jdbc.sql(
            """
            SELECT label, reason, window_size, observed, pass_count, fail_count, flaky_count,
                   flip_count, flip_rate, recent_outcomes, computed_at
            FROM case_health
            WHERE project_id = :p AND suite_key = :s AND case_key = :c AND engine = :e
            """)
        .param("p", projectId)
        .param("s", suiteKey)
        .param("c", caseKey)
        .param("e", engine.wireName())
        .query((rs, i) ->
            new Health(
                CaseHealthWindow.Label.valueOf(rs.getString("label")),
                rs.getString("reason"),
                rs.getInt("window_size"),
                rs.getInt("observed"),
                rs.getInt("pass_count"),
                rs.getInt("fail_count"),
                rs.getInt("flaky_count"),
                rs.getInt("flip_count"),
                rs.getBigDecimal("flip_rate"),
                rs.getString("recent_outcomes"),
                rs.getTimestamp("computed_at").toInstant()))
        .optional();
  }

  /** Newest first by when the result was recorded; index {@code case_results_case_history}. */
  private List<Result> results(
      UUID projectId, String suiteKey, String caseKey, Engine engine, int limit) {
    return jdbc.sql(
            """
            SELECT r.id AS run_id, r.status AS run_status, r.trigger, r.branch, r.commit_sha,
                   t.id AS target_id, a.id AS attempt_id, a.attempt_no, cr.status,
                   cr.duration_ms, cr.retry_count, cr.error_class, cr.fingerprint_id,
                   a.finished_at
            FROM case_results cr
            JOIN attempts a ON a.id = cr.attempt_id
            JOIN run_targets t ON t.id = a.target_id
            JOIN runs r ON r.id = t.run_id
            JOIN suites s ON s.id = r.suite_id
            WHERE cr.case_key = :c AND r.project_id = :p AND s.suite_key = :s AND t.engine = :e
            ORDER BY cr.created_at DESC, cr.id DESC
            LIMIT :limit
            """)
        .param("p", projectId)
        .param("s", suiteKey)
        .param("c", caseKey)
        .param("e", engine.wireName())
        .param("limit", limit)
        .query((rs, i) -> {
          String errorClass = rs.getString("error_class");
          return new Result(
              rs.getObject("run_id", UUID.class),
              RunStatus.valueOf(rs.getString("run_status")),
              Trigger.valueOf(rs.getString("trigger")),
              rs.getString("branch"),
              rs.getString("commit_sha"),
              rs.getObject("target_id", UUID.class),
              rs.getObject("attempt_id", UUID.class),
              rs.getInt("attempt_no"),
              CaseStatus.valueOf(rs.getString("status")),
              rs.getInt("duration_ms"),
              rs.getInt("retry_count"),
              errorClass == null ? null : ErrorClass.valueOf(errorClass),
              rs.getObject("fingerprint_id", UUID.class),
              rs.getTimestamp("finished_at").toInstant());
        })
        .list();
  }
}

package dev.testgrid.api.quality;

import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.quality.QualityRules.CaseFact;
import dev.testgrid.api.quality.QualityRules.TargetFact;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads what the evaluator needs about one finished run. Terminal rows don't change. */
@Repository
public class QualityFactsRepository {

  private final JdbcClient jdbc;

  public QualityFactsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<TargetFact> targets(UUID runId) {
    return jdbc.sql(
            """
            SELECT t.target_key, t.executor, t.engine, t.status,
                   EXISTS (SELECT 1 FROM attempts a JOIN case_results cr ON cr.attempt_id = a.id
                           WHERE a.target_id = t.id) AS has_results,
                   (SELECT a.error_class FROM attempts a WHERE a.target_id = t.id
                    ORDER BY a.attempt_no DESC LIMIT 1) AS error_class
            FROM run_targets t
            WHERE t.run_id = :r
            ORDER BY t.target_key
            """)
        .param("r", runId)
        .query((rs, i) -> {
          String error = rs.getString("error_class");
          return new TargetFact(
              rs.getString("target_key"),
              Executor.valueOf(rs.getString("executor")),
              Engine.fromWireName(rs.getString("engine")),
              TargetStatus.valueOf(rs.getString("status")),
              rs.getBoolean("has_results"),
              error == null ? null : ErrorClass.valueOf(error));
        })
        .list();
  }

  /**
   * The case results of each finished target's newest attempt that has any (the same choice as the
   * run comparison, contracts §8), with the suite revision's {@code critical} flag.
   */
  public List<CaseFact> cases(UUID runId, UUID suiteId) {
    return jdbc.sql(
            """
            SELECT t.target_key, t.engine, cr.case_key, cr.status,
                   coalesce(tc.critical, false) AS critical
            FROM run_targets t
            JOIN LATERAL (
              SELECT a.id FROM attempts a
              WHERE a.target_id = t.id
                AND EXISTS (SELECT 1 FROM case_results x WHERE x.attempt_id = a.id)
              ORDER BY a.attempt_no DESC
              LIMIT 1
            ) latest ON true
            JOIN case_results cr ON cr.attempt_id = latest.id
            LEFT JOIN test_cases tc ON tc.suite_id = :s AND tc.case_key = cr.case_key
            WHERE t.run_id = :r AND t.status IN ('PASSED', 'FAILED', 'TIMED_OUT')
            ORDER BY t.target_key, cr.case_key
            """)
        .param("r", runId)
        .param("s", suiteId)
        .query((rs, i) ->
            new CaseFact(
                rs.getString("target_key"),
                Engine.fromWireName(rs.getString("engine")),
                rs.getString("case_key"),
                CaseStatus.valueOf(rs.getString("status")),
                rs.getBoolean("critical")))
        .list();
  }
}

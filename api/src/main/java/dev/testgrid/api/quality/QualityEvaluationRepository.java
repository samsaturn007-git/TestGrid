package dev.testgrid.api.quality;

import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.domain.QualityOutcome;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.quality.QualityRules.RuleResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code quality_evaluations} (contracts §3): append-only, {@code UNIQUE(run_id, policy_id)}. */
@Repository
public class QualityEvaluationRepository {

  private static final TypeReference<List<RuleResult>> RULES = new TypeReference<>() {};

  public record RunRow(
      UUID id,
      UUID projectId,
      UUID suiteId,
      String suiteKey,
      RunStatus status,
      Instant finishedAt) {}

  public record StoredEvaluation(
      UUID id,
      UUID runId,
      UUID policyId,
      int policyVersion,
      QualityOutcome outcome,
      List<RuleResult> rules,
      Instant evaluatedAt) {}

  private final JdbcClient jdbc;
  private final JsonMapper mapper;

  public QualityEvaluationRepository(JdbcClient jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /**
   * Locks the run row so concurrent evaluations of one run (the loop, a GET, a sweep) take turns
   * and the second finds the first one's row. Only the run is locked, and the run is terminal, so
   * no writer that locks targets first is waiting on anything this transaction holds.
   */
  public Optional<RunRow> lockRun(UUID runId) {
    return jdbc.sql(
            """
            SELECT r.id, r.project_id, r.suite_id, s.suite_key, r.status, r.finished_at
            FROM runs r JOIN suites s ON s.id = r.suite_id
            WHERE r.id = :r FOR UPDATE OF r
            """)
        .param("r", runId)
        .query((rs, i) -> {
          var finished = rs.getTimestamp("finished_at");
          return new RunRow(
              rs.getObject("id", UUID.class),
              rs.getObject("project_id", UUID.class),
              rs.getObject("suite_id", UUID.class),
              rs.getString("suite_key"),
              RunStatus.valueOf(rs.getString("status")),
              finished == null ? null : finished.toInstant());
        })
        .optional();
  }

  /** The run's first evaluation: a run is judged once, by the policy active when it finished. */
  public Optional<StoredEvaluation> first(UUID runId) {
    return jdbc.sql(
            """
            SELECT e.id, e.run_id, e.policy_id, p.version, e.outcome, e.rules::text AS rules,
                   e.evaluated_at
            FROM quality_evaluations e JOIN quality_policies p ON p.id = e.policy_id
            WHERE e.run_id = :r
            ORDER BY e.evaluated_at, e.id
            LIMIT 1
            """)
        .param("r", runId)
        .query((rs, i) ->
            new StoredEvaluation(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("policy_id", UUID.class),
                rs.getInt("version"),
                QualityOutcome.valueOf(rs.getString("outcome")),
                mapper.readValue(rs.getString("rules"), RULES),
                rs.getTimestamp("evaluated_at").toInstant()))
        .optional();
  }

  /** Inserts the evaluation and its QUALITY_EVALUATED event; false if it already existed. */
  public boolean insert(
      UUID runId, UUID policyId, int policyVersion, QualityRules.Evaluation evaluation) {
    Optional<UUID> id =
        jdbc.sql(
                """
                INSERT INTO quality_evaluations (run_id, policy_id, outcome, rules)
                VALUES (:r, :p, :o, CAST(:rules AS jsonb))
                ON CONFLICT (run_id, policy_id) DO NOTHING
                RETURNING id
                """)
            .param("r", runId)
            .param("p", policyId)
            .param("o", evaluation.outcome().name())
            .param("rules", mapper.writeValueAsString(evaluation.rules()))
            .query(UUID.class)
            .optional();
    if (id.isEmpty()) {
      return false;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("evaluationId", id.get().toString());
    payload.put("outcome", evaluation.outcome().name());
    payload.put("policyId", policyId.toString());
    payload.put("policyVersion", policyVersion);
    jdbc.sql(
            """
            INSERT INTO run_events (run_id, type, payload)
            VALUES (:r, :type, CAST(:payload AS jsonb))
            """)
        .param("r", runId)
        .param("type", EventType.QUALITY_EVALUATED.name())
        .param("payload", mapper.writeValueAsString(payload))
        .update();
    return true;
  }

  /** Runs finished in the last day that have no evaluation yet, oldest first. */
  public List<UUID> unevaluatedFinishedRuns(int limit) {
    return jdbc.sql(
            """
            SELECT r.id FROM runs r
            WHERE r.finished_at IS NOT NULL AND r.finished_at > now() - interval '1 day'
              AND NOT EXISTS (SELECT 1 FROM quality_evaluations e WHERE e.run_id = r.id)
            ORDER BY r.finished_at, r.id
            LIMIT :limit
            """)
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }
}

package dev.testgrid.api.failure;

import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.failure.FailureClassifier.Classification;
import dev.testgrid.api.failure.FailureClassifier.Facts;
import dev.testgrid.api.failure.FingerprintV1.Fingerprint;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Writes of PRD-07 FR-1/FR-2: failure groups and their classification. */
@Repository
public class FailureRepository {

  private final JdbcClient jdbc;
  private final JsonMapper mapper;

  public FailureRepository(JdbcClient jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /**
   * Counts {@code occurrences} more of this fingerprint, creating the group on first sight, and
   * locks its row until the transaction ends. The row is classified right after, in the same
   * transaction, so nobody sees the placeholder label. Callers upsert several fingerprints in
   * fingerprint order, so two completions of different runs can't lock them in opposite orders.
   */
  public UUID upsert(UUID projectId, ErrorClass errorClass, Fingerprint fp, int occurrences) {
    return jdbc.sql(
            """
            INSERT INTO failure_fingerprints
              (project_id, algo_version, fingerprint, error_class, category, confidence, rule_id,
               evidence, sample_message, occurrence_count)
            VALUES (:p, :v, :f, :e, 'UNKNOWN', 0, 'R0', '{}'::jsonb, :m, :n)
            ON CONFLICT (project_id, algo_version, fingerprint) DO UPDATE
            SET occurrence_count = failure_fingerprints.occurrence_count + EXCLUDED.occurrence_count,
                -- A transaction that started earlier may commit later: never move backwards.
                last_seen_at = greatest(failure_fingerprints.last_seen_at, now()),
                version = failure_fingerprints.version + 1
            RETURNING id
            """)
        .param("p", projectId)
        .param("v", fp.algoVersion())
        .param("f", fp.value())
        .param("e", errorClass.name())
        .param("m", fp.normalisedMessage())
        .param("n", occurrences)
        .query(UUID.class)
        .single();
  }

  /**
   * The facts the rules read, over every case-level and attempt-level occurrence of the group.
   * Index scans on {@code case_results_fingerprint} and {@code attempts_fingerprint}; O(k) in the
   * group's occurrences.
   */
  public Facts facts(UUID fingerprintId) {
    ErrorClass errorClass =
        jdbc.sql("SELECT error_class FROM failure_fingerprints WHERE id = :id")
            .param("id", fingerprintId)
            .query((rs, i) -> ErrorClass.valueOf(rs.getString(1)))
            .single();
    return jdbc.sql(
            """
            WITH occ AS (
              SELECT cr.status, a.id AS attempt_id, t.run_id, t.engine
              FROM case_results cr
              JOIN attempts a ON a.id = cr.attempt_id
              JOIN run_targets t ON t.id = a.target_id
              WHERE cr.fingerprint_id = :id
              UNION ALL
              SELECT a.status, a.id, t.run_id, t.engine
              FROM attempts a
              JOIN run_targets t ON t.id = a.target_id
              WHERE a.fingerprint_id = :id
            )
            SELECT count(*) AS occurrences,
                   count(DISTINCT attempt_id) AS attempts,
                   count(DISTINCT run_id) AS runs,
                   count(DISTINCT engine) AS engines,
                   count(*) FILTER (WHERE status = 'FLAKY') AS passed_on_retry,
                   array_agg(DISTINCT status) AS statuses
            FROM occ
            """)
        .param("id", fingerprintId)
        .query((rs, i) ->
            new Facts(
                errorClass,
                rs.getInt("occurrences"),
                rs.getInt("attempts"),
                rs.getInt("runs"),
                rs.getInt("engines"),
                rs.getInt("passed_on_retry"),
                strings(rs.getArray("statuses"))))
        .single();
  }

  public void classify(UUID fingerprintId, Classification c) {
    jdbc.sql(
            """
            UPDATE failure_fingerprints
            SET category = :c, confidence = :conf, rule_id = :r, evidence = CAST(:ev AS jsonb)
            WHERE id = :id
            """)
        .param("id", fingerprintId)
        .param("c", c.category().name())
        .param("conf", c.confidence())
        .param("r", c.ruleId())
        .param("ev", mapper.writeValueAsString(c.evidence()))
        .update();
  }

  private static Set<String> strings(Array array) throws SQLException {
    if (array == null) {
      return Set.of();
    }
    Object[] values = (Object[]) array.getArray();
    return Set.copyOf(Arrays.stream(values).map(String::valueOf).toList());
  }
}

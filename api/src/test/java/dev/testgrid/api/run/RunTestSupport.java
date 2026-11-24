package dev.testgrid.api.run;

import dev.testgrid.api.project.LocalProject;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Shared setup for run API tests: clean run tables and a restricted extra suite. */
final class RunTestSupport {

  static final String RUNS_URL = "/api/v1/projects/" + LocalProject.ID + "/runs";

  private RunTestSupport() {}

  static void clearRuns(JdbcClient jdbc) {
    // TRUNCATE doesn't fire run_events' row-level append-only trigger.
    jdbc.sql(
            "TRUNCATE artifacts, attempt_heartbeats, case_results, attempts, failure_fingerprints, audit_events, run_events, run_targets, runs")
        .update();
  }

  /** A current suite that allows only chromium and the PASS fixture mode. */
  static void ensureChromiumOnlySuite(JdbcClient jdbc) {
    jdbc.sql(
            """
            INSERT INTO suites (project_id, suite_key, revision, display_name, is_current,
                                timeout_ms, playwright_retries, allowed_engines, fixture_modes)
            VALUES (:p, 'chromium-only', 'aaaaaaaaaaaa', 'Chromium only', true, 60000, 0,
                    ARRAY['chromium'], ARRAY['PASS'])
            ON CONFLICT (project_id, suite_key, revision) DO UPDATE SET is_current = true
            """)
        .param("p", LocalProject.ID)
        .update();
  }

  static String body(String suiteKey, String targetsJson, String extra) {
    return "{\"suiteKey\":\""
        + suiteKey
        + "\",\"targets\":"
        + targetsJson
        + ",\"trigger\":\"MANUAL\",\"idempotencyKey\":\""
        + UUID.randomUUID()
        + "\""
        + (extra.isEmpty() ? "" : "," + extra)
        + "}";
  }

  static String engines(String... engines) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < engines.length; i++) {
      out.append(i == 0 ? "" : ",")
          .append("{\"executor\":\"LOCAL\",\"engine\":\"")
          .append(engines[i])
          .append("\"}");
    }
    return out.append(']').toString();
  }

  static UUID currentSuiteId(JdbcClient jdbc, String suiteKey) {
    return jdbc.sql("SELECT id FROM suites WHERE project_id = :p AND suite_key = :k AND is_current")
        .param("p", LocalProject.ID)
        .param("k", suiteKey)
        .query(UUID.class)
        .single();
  }

  /** Inserts runs straight into the table with a chosen queued_at, bypassing the API. */
  static UUID insertRun(JdbcClient jdbc, UUID projectId, UUID suiteId, String queuedAtSql) {
    return jdbc.sql(
            "INSERT INTO runs (project_id, suite_id, status, trigger, idempotency_key, request_hash,"
                + " queued_at) VALUES (:p, :s, 'QUEUED', 'MANUAL', :k, :h, "
                + queuedAtSql
                + ") RETURNING id")
        .param("p", projectId)
        .param("s", suiteId)
        .param("k", "seed-" + UUID.randomUUID())
        .param("h", new byte[32])
        .query(UUID.class)
        .single();
  }

  static long count(JdbcClient jdbc, String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}

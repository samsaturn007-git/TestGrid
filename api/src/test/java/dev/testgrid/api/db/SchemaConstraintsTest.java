package dev.testgrid.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD-02 FR-1: the invariants in V1__core.sql hold even when the API is bypassed. Each test runs in
 * a transaction that is rolled back.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@Transactional
class SchemaConstraintsTest {

  @Autowired private JdbcClient jdbc;
  private UUID suiteId;

  @BeforeEach
  void currentSuite() {
    suiteId =
        jdbc.sql("SELECT id FROM suites WHERE suite_key = 'checkout-smoke' AND is_current")
            .query(UUID.class)
            .single();
  }

  @Test
  void serverIsThePinnedPostgresVersion() {
    assertThat(jdbc.sql("SHOW server_version").query(String.class).single()).startsWith("18.7");
  }

  @Test
  void migrationsSeedTheFixedLocalProject() {
    assertThat(
            jdbc.sql("SELECT slug FROM projects WHERE id = :id")
                .param("id", LocalProject.ID)
                .query(String.class)
                .single())
        .isEqualTo(LocalProject.SLUG);
  }

  @Test
  void requiredIndexesExist() {
    List<String> defs =
        jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'")
            .query(String.class)
            .list();
    assertThat(defs)
        .anyMatch(
            d -> d.contains("ON public.runs USING btree (project_id, queued_at DESC, id DESC)"))
        .anyMatch(d -> d.contains("ON public.runs USING btree (project_id, status)"))
        .anyMatch(d -> d.contains("ON public.run_targets USING btree (run_id, target_key)"))
        .anyMatch(d ->
            d.contains("ON public.run_targets USING btree (status, not_before)")
                && d.contains("WHERE (status = 'QUEUED'::text)"))
        .anyMatch(d -> d.contains("ON public.run_events USING btree (run_id, id)"))
        .anyMatch(d ->
            d.startsWith("CREATE UNIQUE INDEX")
                && d.contains("ON public.runs USING btree (project_id, idempotency_key)"))
        .anyMatch(d ->
            d.startsWith("CREATE UNIQUE INDEX")
                && d.contains(
                    "ON public.suites USING btree (project_id, suite_key) WHERE is_current"));
  }

  @Test
  void duplicateIdempotencyKeyInAProjectIsRejected() {
    insertRun("key-1", "QUEUED", false);
    assertThatThrownBy(() -> insertRun("key-1", "QUEUED", false))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("runs_project_id_idempotency_key_key");
  }

  @Test
  void theSameIdempotencyKeyIsAllowedInAnotherProject() {
    UUID other =
        jdbc.sql("INSERT INTO projects (slug, name) VALUES ('other', 'Other') RETURNING id")
            .query(UUID.class)
            .single();
    UUID otherSuite =
        jdbc.sql(
                "INSERT INTO suites (project_id, suite_key, revision, display_name, timeout_ms,"
                    + " playwright_retries, allowed_engines, fixture_modes) VALUES (:p, 's',"
                    + " 'eeeeeeeeeeee', 'S', 1000, 0, ARRAY['chromium'], ARRAY[]::text[])"
                    + " RETURNING id")
            .param("p", other)
            .query(UUID.class)
            .single();
    insertRun("shared-key", "QUEUED", false);
    int inserted =
        jdbc.sql(
                "INSERT INTO runs (project_id, suite_id, status, trigger, idempotency_key,"
                    + " request_hash) VALUES (:p, :s, 'QUEUED', 'MANUAL', 'shared-key', :h)")
            .param("p", other)
            .param("s", otherSuite)
            .param("h", new byte[32])
            .update();
    assertThat(inserted).isEqualTo(1);
  }

  @Test
  void nonCanonicalStatusSpellingIsRejected() {
    assertThatThrownBy(() -> insertRun("key-2", "CANCELED", false))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("runs_status_check");
  }

  @Test
  void terminalRunNeedsFinishedAtAndActiveRunMustNotHaveIt() {
    assertThatThrownBy(() -> insertRun("key-3", "PASSED", false))
        .hasMessageContaining("runs_finished_iff_terminal");
  }

  @Test
  void activeRunWithFinishedAtIsRejected() {
    assertThatThrownBy(() -> insertRun("key-4", "QUEUED", true))
        .hasMessageContaining("runs_finished_iff_terminal");
  }

  @Test
  void runCannotUseASuiteFromAnotherProject() {
    UUID other =
        jdbc.sql("INSERT INTO projects (slug, name) VALUES ('other', 'Other') RETURNING id")
            .query(UUID.class)
            .single();
    assertThatThrownBy(() ->
            jdbc.sql(
                    "INSERT INTO runs (project_id, suite_id, status, trigger, idempotency_key,"
                        + " request_hash) VALUES (:p, :s, 'QUEUED', 'MANUAL', 'k', :h)")
                .param("p", other)
                .param("s", suiteId)
                .param("h", new byte[32])
                .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("runs_suite_in_project");
  }

  @Test
  void onlyOneRevisionPerSuiteKeyCanBeCurrent() {
    assertThatThrownBy(() ->
            jdbc.sql(
                    "INSERT INTO suites (project_id, suite_key, revision, display_name,"
                        + " is_current, timeout_ms, playwright_retries, allowed_engines,"
                        + " fixture_modes) VALUES (:p, 'checkout-smoke', '000000000000', 'x',"
                        + " true, 1000, 0, ARRAY['chromium'], ARRAY[]::text[])")
                .param("p", LocalProject.ID)
                .update())
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("suites_one_current_per_key");
  }

  @Test
  void unknownEngineOnATargetIsRejected() {
    UUID runId = insertRun("key-5", "QUEUED", false);
    assertThatThrownBy(() ->
            jdbc.sql(
                    "INSERT INTO run_targets (run_id, target_key, executor, engine, status)"
                        + " VALUES (:r, 'local:edge:0', 'LOCAL', 'edge', 'QUEUED')")
                .param("r", runId)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("run_targets_engine_check");
  }

  @Test
  void runEventsRejectUpdates() {
    long eventId = insertEvent(insertRun("key-6", "QUEUED", false));
    assertThatThrownBy(() ->
            jdbc.sql("UPDATE run_events SET type = 'RUN_FINISHED' WHERE id = :id")
                .param("id", eventId)
                .update())
        .hasMessageContaining("run_events is append-only (UPDATE rejected)");
  }

  @Test
  void runEventsRejectDeletes() {
    long eventId = insertEvent(insertRun("key-7", "QUEUED", false));
    assertThatThrownBy(
            () -> jdbc.sql("DELETE FROM run_events WHERE id = :id").param("id", eventId).update())
        .hasMessageContaining("run_events is append-only (DELETE rejected)");
  }

  @Test
  void unknownEventTypeIsRejected() {
    UUID runId = insertRun("key-8", "QUEUED", false);
    assertThatThrownBy(() ->
            jdbc.sql("INSERT INTO run_events (run_id, type) VALUES (:r, 'RUN_EXPLODED')")
                .param("r", runId)
                .update())
        .hasMessageContaining("run_events_type_check");
  }

  private UUID insertRun(String key, String status, boolean finished) {
    return jdbc.sql(
            "INSERT INTO runs (project_id, suite_id, status, trigger, idempotency_key, request_hash,"
                + " finished_at) VALUES (:p, :s, :status, 'MANUAL', :key, :h,"
                + " CASE WHEN :finished THEN now() END) RETURNING id")
        .param("p", LocalProject.ID)
        .param("s", suiteId)
        .param("status", status)
        .param("key", key)
        .param("h", new byte[32])
        .param("finished", finished)
        .query(UUID.class)
        .single();
  }

  private long insertEvent(UUID runId) {
    return jdbc.sql(
            "INSERT INTO run_events (run_id, type, payload) VALUES (:r, 'RUN_CREATED',"
                + " '{\"targets\": 1}'::jsonb) RETURNING id")
        .param("r", runId)
        .query(Long.class)
        .single();
  }
}

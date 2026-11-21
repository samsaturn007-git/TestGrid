package dev.testgrid.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD-04 FR-1 / T1: the invariants in V4__dispatch.sql hold even when the API is bypassed. Each
 * test runs in a transaction that is rolled back.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@Transactional
class DispatchSchemaTest {

  @Autowired private JdbcClient jdbc;

  private long outboxRow() {
    return jdbc.sql(
            """
            INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, traceparent)
            VALUES ('run_target', :id, 'TARGET_DISPATCHED', '{"dispatchGeneration":1}',
                    '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01')
            RETURNING id
            """)
        .param("id", UUID.randomUUID())
        .query(Long.class)
        .single();
  }

  private int update(String sql, long id) {
    return jdbc.sql(sql).param("id", id).update();
  }

  @Test
  void requiredDispatchIndexesExist() {
    List<String> defs =
        jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'")
            .query(String.class)
            .list();
    assertThat(defs)
        .anyMatch(d ->
            d.contains("ON public.outbox_events USING btree (id)")
                && d.contains("WHERE (published_at IS NULL)"))
        .anyMatch(d ->
            d.contains("ON public.run_targets USING btree (queued_at, id)")
                && d.contains("(status = 'QUEUED'::text)")
                && d.contains("(dispatched_at IS NULL)"))
        .anyMatch(d ->
            d.startsWith("CREATE UNIQUE INDEX")
                && d.contains("ON public.workers USING btree (name)")
                && d.contains("WHERE (state <> 'LOST'::text)"));
  }

  @Test
  void outboxDeliveryBookkeepingCanChange() {
    long id = outboxRow();
    assertThat(
            update(
                "UPDATE outbox_events SET publish_attempts = publish_attempts + 1,"
                    + " last_error = 'nack', next_attempt_at = now() + interval '1 second'"
                    + " WHERE id = :id",
                id))
        .isEqualTo(1);
    assertThat(update("UPDATE outbox_events SET published_at = now() WHERE id = :id", id))
        .isEqualTo(1);
  }

  @Test
  void outboxContentIsImmutable() {
    long id = outboxRow();
    assertThatThrownBy(() ->
            update(
                "UPDATE outbox_events SET payload = '{\"dispatchGeneration\":2}' WHERE id = :id",
                id))
        .hasMessageContaining("content is immutable");
  }

  @Test
  void aPublishedOutboxRowStaysPublished() {
    long id = outboxRow();
    update("UPDATE outbox_events SET published_at = now() WHERE id = :id", id);
    assertThatThrownBy(
            () -> update("UPDATE outbox_events SET published_at = NULL WHERE id = :id", id))
        .hasMessageContaining("is already published");
  }

  @Test
  void outboxRowsCannotBeDeleted() {
    long id = outboxRow();
    assertThatThrownBy(() -> update("DELETE FROM outbox_events WHERE id = :id", id))
        .hasMessageContaining("outbox_events rows are kept (DELETE rejected)");
  }

  // One rejected statement per test: after an error Postgres aborts the rest of the transaction.
  @Test
  void outboxRejectsAMalformedTraceparent() {
    assertThatThrownBy(() ->
            jdbc.sql(
                    """
                    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload,
                                               traceparent)
                    VALUES ('run_target', gen_random_uuid(), 'TARGET_DISPATCHED', '{}', 'nope')
                    """)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("outbox_events_traceparent_check");
  }

  @Test
  void outboxRejectsUnknownEventTypes() {
    assertThatThrownBy(() ->
            jdbc.sql(
                    """
                    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                    VALUES ('run_target', gen_random_uuid(), 'TARGET_FINISHED', '{}')
                    """)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("outbox_events_event_type_check");
  }

  private void insertWorker(String name, String state, String engines) {
    jdbc.sql(
            """
            INSERT INTO workers (name, version, executor, engines, slots, state)
            VALUES (:n, '0.0.1', 'LOCAL', CAST(:e AS text[]), 1, :s)
            """)
        .param("n", name)
        .param("s", state)
        .param("e", engines)
        .update();
  }

  @Test
  void onlyOneLiveWorkerRowPerName() {
    insertWorker("worker-a", "LOST", "{chromium}");
    insertWorker("worker-a", "ACTIVE", "{chromium}");
    assertThatThrownBy(() -> insertWorker("worker-a", "DRAINING", "{chromium}"))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("workers_one_live_per_name");
  }

  @Test
  void workerStateUsesTheCanonicalSpellings() {
    assertThatThrownBy(() -> insertWorker("worker-b", "DEAD", "{chromium}"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("workers_state_check");
  }

  @Test
  void workerEnginesMustBeKnown() {
    assertThatThrownBy(() -> insertWorker("worker-c", "ACTIVE", "{chrome}"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("workers_engines_check");
  }

  @Test
  void schedulerSettingsIsASingleRow() {
    assertThat(jdbc.sql("SELECT count(*) FROM scheduler_settings").query(Long.class).single())
        .isEqualTo(1);
    assertThatThrownBy(() ->
            jdbc.sql(
                    "INSERT INTO scheduler_settings (global_max_sessions, max_attempts,"
                        + " lease_ttl_s) VALUES (3, 3, 30)")
                .update())
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void theSettingsRowKeyCanOnlyBeTrue() {
    assertThatThrownBy(() ->
            jdbc.sql(
                    "INSERT INTO scheduler_settings (id, global_max_sessions, max_attempts,"
                        + " lease_ttl_s) VALUES (false, 3, 3, 30)")
                .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("scheduler_settings_id_check");
  }

  @Test
  void onlyTheFifoPolicyExistsBeforePrd11() {
    assertThatThrownBy(
            () -> jdbc.sql("UPDATE scheduler_settings SET policy = 'LPT_AGING'").update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("scheduler_settings_policy_check");
  }
}

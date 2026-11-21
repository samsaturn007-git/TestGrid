package dev.testgrid.api.dispatch;

import dev.testgrid.api.domain.Engine;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the outbox publisher (PRD-04 FR-3). Only the delivery columns are ever updated. */
@Repository
public class OutboxRepository {

  private final JdbcClient jdbc;

  public OutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** An unpublished row plus the routing facts of its target (engine and run never change). */
  public record Pending(
      long id,
      UUID targetId,
      UUID runId,
      Engine engine,
      String payload,
      String traceparent,
      int publishAttempts) {}

  static final String LOCK_BATCH =
      """
      SELECT o.id, o.aggregate_id, o.payload::text AS payload, o.traceparent,
             o.publish_attempts, t.run_id, t.engine
      FROM outbox_events o
      JOIN run_targets t ON t.id = o.aggregate_id
      WHERE o.published_at IS NULL AND o.next_attempt_at <= now()
      ORDER BY o.id
      LIMIT :limit
      FOR UPDATE OF o SKIP LOCKED
      """;

  /**
   * The oldest unpublished rows that are due, locked until this transaction ends. SKIP LOCKED lets
   * a second publisher take the next batch instead of re-sending this one. Served by the partial
   * index {@code outbox_events_unpublished}.
   */
  public List<Pending> lockBatch(int limit) {
    return jdbc.sql(LOCK_BATCH)
        .param("limit", limit)
        .query((rs, i) ->
            new Pending(
                rs.getLong("id"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                Engine.fromWireName(rs.getString("engine")),
                rs.getString("payload"),
                rs.getString("traceparent"),
                rs.getInt("publish_attempts")))
        .list();
  }

  public int markPublished(List<Long> ids) {
    if (ids.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            """
            UPDATE outbox_events
            SET published_at = now(), publish_attempts = publish_attempts + 1, last_error = NULL
            WHERE id IN (:ids) AND published_at IS NULL
            """)
        .param("ids", ids)
        .update();
  }

  /** A nack, a return or a timeout: try again after {@code delayMs}. */
  public int markFailed(long id, String error, long delayMs) {
    return jdbc.sql(
            """
            UPDATE outbox_events
            SET publish_attempts = publish_attempts + 1, last_error = :e,
                next_attempt_at = now() + make_interval(secs => :s)
            WHERE id = :id AND published_at IS NULL
            """)
        .param("id", id)
        .param("e", error)
        .param("s", delayMs / 1000.0)
        .update();
  }

  /** Unpublished rows, due or not: the outbox lag an operator (and PRD-12's metric) watches. */
  public long unpublished() {
    return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
        .query(Long.class)
        .single();
  }
}

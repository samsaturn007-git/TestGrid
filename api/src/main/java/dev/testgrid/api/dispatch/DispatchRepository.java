package dev.testgrid.api.dispatch;

import dev.testgrid.api.domain.Engine;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the dispatcher (ARCHITECTURE §3). Every method runs in the dispatcher's transaction. */
@Repository
public class DispatchRepository {

  /**
   * Key of the transaction-level advisory lock that serialises dispatch ticks. Any API instance can
   * run the dispatcher; only one tick at a time computes and spends the free capacity.
   */
  static final long DISPATCH_LOCK_KEY = 0x7467_6469_7370_0001L; // "tgdisp" + 1

  private final JdbcClient jdbc;

  public DispatchRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Blocks until this transaction holds the dispatch lock; released at commit or rollback. */
  public void lock() {
    jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", DISPATCH_LOCK_KEY).query().singleRow();
  }

  /**
   * Sessions in use or promised: active attempts plus targets handed to the broker that no worker
   * has claimed yet. One statement, so both counts come from the same snapshot; a claim moves a
   * target from the second count to the first in one transaction, so the sum never dips.
   */
  public record Capacity(int active, int dispatchedUnclaimed) {
    int used() {
      return active + dispatchedUnclaimed;
    }
  }

  public Capacity capacity() {
    return jdbc.sql(
            """
            SELECT (SELECT count(*) FROM attempts
                     WHERE status IN ('CLAIMED', 'RUNNING'))::int AS active,
                   (SELECT count(*) FROM run_targets
                     WHERE status = 'QUEUED' AND dispatched_at IS NOT NULL)::int AS dispatched
            """)
        .query((rs, i) -> new Capacity(rs.getInt("active"), rs.getInt("dispatched")))
        .single();
  }

  public record Candidate(UUID targetId, UUID runId, Engine engine) {}

  static final String PICK_FIFO =
      """
      SELECT t.id, t.run_id, t.engine
      FROM run_targets t
      JOIN runs r ON r.id = t.run_id
      WHERE t.status = 'QUEUED'
        AND t.dispatched_at IS NULL
        AND (t.not_before IS NULL OR t.not_before <= now())
        AND t.attempt_count < t.max_attempts
        AND r.cancel_requested_at IS NULL
      ORDER BY t.queued_at, t.id
      LIMIT :limit
      FOR UPDATE OF t SKIP LOCKED
      """;

  /**
   * FIFO policy: the oldest dispatchable targets, locked. Served by {@code run_targets_dispatchable
   * (queued_at, id) WHERE status = 'QUEUED' AND dispatched_at IS NULL}. SKIP LOCKED steps over a
   * row a claim or the reaper holds instead of waiting on it.
   */
  public List<Candidate> pickFifo(int limit) {
    return jdbc.sql(PICK_FIFO)
        .param("limit", limit)
        .query((rs, i) ->
            new Candidate(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                Engine.fromWireName(rs.getString("engine"))))
        .list();
  }

  /** The new dispatch generation; the old one becomes stale for any claim that carries it. */
  public int markDispatched(UUID targetId) {
    return jdbc.sql(
            """
            UPDATE run_targets
            SET dispatched_at = now(), dispatch_generation = dispatch_generation + 1,
                version = version + 1
            WHERE id = :t AND status = 'QUEUED' AND dispatched_at IS NULL
            RETURNING dispatch_generation
            """)
        .param("t", targetId)
        .query(Integer.class)
        .optional()
        .orElseThrow(() -> new IllegalStateException("locked target " + targetId + " moved"));
  }

  public long insertOutbox(UUID targetId, String payloadJson, String traceparent) {
    return jdbc.sql(
            """
            INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload,
                                       traceparent)
            VALUES ('run_target', :t, 'TARGET_DISPATCHED', CAST(:p AS jsonb), :tp)
            RETURNING id
            """)
        .param("t", targetId)
        .param("p", payloadJson)
        .param("tp", traceparent)
        .query(Long.class)
        .single();
  }
}

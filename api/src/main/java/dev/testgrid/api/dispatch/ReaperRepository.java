package dev.testgrid.api.dispatch;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the lease reaper (ARCHITECTURE §4). Both scans use {@code FOR UPDATE … SKIP LOCKED}: a
 * row a completion or a claim holds right now is left for the next pass, which then sees what that
 * transaction decided. Lock order is attempt before target, as in {@code complete}, so the two
 * never deadlock. Every change is a conditional UPDATE whose row count the caller checks.
 */
@Repository
public class ReaperRepository {

  private final JdbcClient jdbc;

  public ReaperRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record ExpiredLease(
      UUID attemptId,
      UUID targetId,
      UUID runId,
      int attemptNo,
      String workerId,
      Instant leasedUntil,
      int attemptCount,
      int maxAttempts,
      boolean cancelRequested) {}

  static final String EXPIRED_LEASES =
      """
      SELECT a.id AS attempt_id, a.target_id, t.run_id, a.attempt_no, a.worker_id, a.leased_until,
             t.attempt_count, t.max_attempts, r.cancel_requested_at IS NOT NULL AS cancel_requested
      FROM attempts a
      JOIN run_targets t ON t.id = a.target_id
      JOIN runs r ON r.id = t.run_id
      WHERE a.status IN ('CLAIMED', 'RUNNING') AND a.leased_until < now()
      ORDER BY a.leased_until
      LIMIT :limit
      FOR UPDATE OF a, t SKIP LOCKED
      """;

  /** Active attempts whose lease ran out by the database clock, oldest first, locked. */
  public List<ExpiredLease> lockExpiredLeases(int limit) {
    return jdbc.sql(EXPIRED_LEASES)
        .param("limit", limit)
        .query((rs, i) ->
            new ExpiredLease(
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("target_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getString("worker_id"),
                rs.getTimestamp("leased_until").toInstant(),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getBoolean("cancel_requested")))
        .list();
  }

  /** ABANDONED keeps the attempt (and its history) but ends it; no completion key is set. */
  public int abandonAttempt(UUID attemptId, String detail) {
    return jdbc.sql(
            """
            UPDATE attempts
            SET status = 'ABANDONED', error_class = 'LEASE_EXPIRED', error_detail_redacted = :d,
                finished_at = now(), version = version + 1
            WHERE id = :id AND status IN ('CLAIMED', 'RUNNING')
            """)
        .param("id", attemptId)
        .param("d", detail)
        .update();
  }

  /**
   * Back to QUEUED for another attempt: not dispatched (the dispatcher hands it out again with a
   * new generation, which makes the old message stale) and not before the backoff has passed.
   */
  public Instant requeueAfterAttempt(UUID targetId, long backoffMs) {
    return jdbc.sql(
            """
            UPDATE run_targets
            SET status = 'QUEUED', dispatched_at = NULL,
                not_before = now() + make_interval(secs => :ms / 1000.0), version = version + 1
            WHERE id = :t AND status IN ('CLAIMED', 'RUNNING')
            RETURNING not_before
            """)
        .param("t", targetId)
        .param("ms", backoffMs)
        .query((rs, i) -> rs.getTimestamp("not_before").toInstant())
        .optional()
        .orElseThrow(
            () -> new IllegalStateException("locked active target " + targetId + " moved"));
  }

  public record StuckDispatch(
      UUID targetId,
      UUID runId,
      int dispatchGeneration,
      int attemptCount,
      int maxAttempts,
      Instant dispatchedAt,
      boolean cancelRequested) {

    /** Dispatches that never led to a claim: each claim uses up exactly one generation. */
    int unclaimedDispatches() {
      return dispatchGeneration - attemptCount;
    }
  }

  static final String STUCK_DISPATCHES =
      """
      SELECT t.id, t.run_id, t.dispatch_generation, t.attempt_count, t.max_attempts,
             t.dispatched_at, r.cancel_requested_at IS NOT NULL AS cancel_requested
      FROM run_targets t
      JOIN runs r ON r.id = t.run_id
      WHERE t.status = 'QUEUED'
        AND t.dispatched_at < now() - make_interval(secs => :timeoutMs / 1000.0)
      ORDER BY t.dispatched_at
      LIMIT :limit
      FOR UPDATE OF t SKIP LOCKED
      """;

  /** QUEUED targets handed to the broker longer ago than the timeout and never claimed, locked. */
  public List<StuckDispatch> lockStuckDispatches(long timeoutMs, int limit) {
    return jdbc.sql(STUCK_DISPATCHES)
        .param("timeoutMs", timeoutMs)
        .param("limit", limit)
        .query((rs, i) ->
            new StuckDispatch(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("dispatch_generation"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("dispatched_at").toInstant(),
                rs.getBoolean("cancel_requested")))
        .list();
  }

  /** Forget the lost dispatch; the dispatcher's next tick sends a new generation. */
  public int undispatch(UUID targetId) {
    return jdbc.sql(
            """
            UPDATE run_targets SET dispatched_at = NULL, version = version + 1
            WHERE id = :t AND status = 'QUEUED' AND dispatched_at IS NOT NULL
            """)
        .param("t", targetId)
        .update();
  }

  /** A QUEUED target that will never run: INFRA_ERROR or CANCELLED without another attempt. */
  public int finishQueued(UUID targetId, String status) {
    return jdbc.sql(
            """
            UPDATE run_targets
            SET status = :s, finished_at = now(), version = version + 1
            WHERE id = :t AND status = 'QUEUED'
            """)
        .param("t", targetId)
        .param("s", status)
        .update();
  }
}

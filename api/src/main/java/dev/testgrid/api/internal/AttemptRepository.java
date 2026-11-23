package dev.testgrid.api.internal;

import dev.testgrid.api.domain.AttemptStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.domain.FixtureMode;
import dev.testgrid.api.domain.RunAggregate;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.TargetStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the lease protocol (ARCHITECTURE §4). Every state change is a conditional {@code UPDATE …
 * WHERE status IN (…)}; callers check the row count, so a transition that lost a race is never
 * silently applied. Lease times always come from the database's {@code now()}.
 */
@Repository
public class AttemptRepository {

  private final JdbcClient jdbc;

  public AttemptRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record ClaimTarget(
      UUID targetId,
      UUID runId,
      Engine engine,
      TargetStatus status,
      int attemptCount,
      int dispatchGeneration,
      boolean dispatched,
      boolean cancelRequested,
      FixtureMode fixtureMode,
      Integer fixtureSeed,
      UUID suiteId,
      String suiteKey,
      String revision,
      int timeoutMs) {}

  static final String LOCK_FOR_CLAIM =
      """
      SELECT t.id AS target_id, t.run_id, t.engine, t.status, t.attempt_count,
             t.dispatch_generation, t.dispatched_at IS NOT NULL AS dispatched,
             r.cancel_requested_at IS NOT NULL AS cancel_requested,
             r.fixture_mode, r.fixture_seed,
             s.id AS suite_id, s.suite_key, s.revision, s.timeout_ms
      FROM run_targets t
      JOIN runs r ON r.id = t.run_id
      JOIN suites s ON s.id = r.suite_id
      WHERE t.id = :t
      FOR UPDATE OF t
      """;

  /**
   * The target a dispatch message names, locked for this transaction (PRD-04 FR-5). No SKIP LOCKED:
   * a duplicate delivery waits for the claim that holds the row, then sees it {@code CLAIMED}.
   */
  public Optional<ClaimTarget> lockForClaim(UUID targetId) {
    return jdbc.sql(LOCK_FOR_CLAIM)
        .param("t", targetId)
        .query((rs, i) ->
            new ClaimTarget(
                rs.getObject("target_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                Engine.fromWireName(rs.getString("engine")),
                TargetStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                rs.getInt("dispatch_generation"),
                rs.getBoolean("dispatched"),
                rs.getBoolean("cancel_requested"),
                rs.getString("fixture_mode") == null
                    ? null
                    : FixtureMode.valueOf(rs.getString("fixture_mode")),
                rs.getObject("fixture_seed", Integer.class),
                rs.getObject("suite_id", UUID.class),
                rs.getString("suite_key"),
                rs.getString("revision"),
                rs.getInt("timeout_ms")))
        .optional();
  }

  public List<String> caseKeys(UUID suiteId) {
    return jdbc.sql("SELECT case_key FROM test_cases WHERE suite_id = :s ORDER BY case_key")
        .param("s", suiteId)
        .query(String.class)
        .list();
  }

  public record NewAttempt(UUID id, Instant leasedUntil) {}

  public NewAttempt insertAttempt(
      UUID targetId, int attemptNo, String workerId, byte[] tokenHash, int ttlSeconds) {
    return jdbc.sql(
            """
            INSERT INTO attempts (target_id, attempt_no, worker_id, status, lease_token_hash,
                                  leased_until)
            VALUES (:t, :no, :w, 'CLAIMED', :h, now() + make_interval(secs => :ttl))
            RETURNING id, leased_until
            """)
        .param("t", targetId)
        .param("no", attemptNo)
        .param("w", workerId)
        .param("h", tokenHash)
        .param("ttl", ttlSeconds)
        .query(
            (rs, i) -> new NewAttempt(rs.getObject("id", UUID.class), instant(rs, "leased_until")))
        .single();
  }

  public int markTargetClaimed(UUID targetId) {
    return jdbc.sql(
            """
            UPDATE run_targets
            SET status = 'CLAIMED', attempt_count = attempt_count + 1, version = version + 1
            WHERE id = :t AND status = 'QUEUED'
            """)
        .param("t", targetId)
        .update();
  }

  public record LockedAttempt(
      UUID id,
      UUID targetId,
      UUID runId,
      int attemptNo,
      String workerId,
      AttemptStatus status,
      byte[] tokenHash,
      boolean leaseValid,
      String completionKey,
      TargetStatus targetStatus,
      boolean cancelRequested,
      UUID suiteId,
      int timeoutMs,
      int playwrightRetries) {}

  /**
   * Locks the attempt and its target row for the rest of the transaction. Lock order is always
   * target before run, in claim and complete alike, so the two can't deadlock. {@code leaseValid}
   * is evaluated against the database clock in the same statement.
   */
  public Optional<LockedAttempt> lockAttempt(UUID attemptId) {
    return jdbc.sql(
            """
            SELECT a.id, a.target_id, t.run_id, a.attempt_no, a.worker_id, a.status,
                   a.lease_token_hash, a.leased_until > now() AS lease_valid, a.completion_key,
                   t.status AS target_status, r.cancel_requested_at IS NOT NULL AS cancel_requested,
                   s.id AS suite_id, s.timeout_ms, s.playwright_retries
            FROM attempts a
            JOIN run_targets t ON t.id = a.target_id
            JOIN runs r ON r.id = t.run_id
            JOIN suites s ON s.id = r.suite_id
            WHERE a.id = :id
            FOR UPDATE OF a, t
            """)
        .param("id", attemptId)
        .query((rs, i) ->
            new LockedAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("target_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getString("worker_id"),
                AttemptStatus.valueOf(rs.getString("status")),
                rs.getBytes("lease_token_hash"),
                rs.getBoolean("lease_valid"),
                rs.getString("completion_key"),
                TargetStatus.valueOf(rs.getString("target_status")),
                rs.getBoolean("cancel_requested"),
                rs.getObject("suite_id", UUID.class),
                rs.getInt("timeout_ms"),
                rs.getInt("playwright_retries")))
        .optional();
  }

  public Optional<Instant> markStarted(UUID attemptId, int ttlSeconds) {
    return jdbc.sql(
            """
            UPDATE attempts
            SET status = 'RUNNING', started_at = now(), last_heartbeat_at = now(),
                leased_until = now() + make_interval(secs => :ttl), version = version + 1
            WHERE id = :id AND status = 'CLAIMED'
            RETURNING leased_until
            """)
        .param("id", attemptId)
        .param("ttl", ttlSeconds)
        .query((rs, i) -> instant(rs, "leased_until"))
        .optional();
  }

  public int markTargetRunning(UUID targetId) {
    return jdbc.sql(
            "UPDATE run_targets SET status = 'RUNNING', version = version + 1"
                + " WHERE id = :t AND status = 'CLAIMED'")
        .param("t", targetId)
        .update();
  }

  public Optional<Instant> extendLease(UUID attemptId, int ttlSeconds) {
    return jdbc.sql(
            """
            UPDATE attempts
            SET leased_until = now() + make_interval(secs => :ttl), last_heartbeat_at = now(),
                version = version + 1
            WHERE id = :id AND status IN ('CLAIMED', 'RUNNING')
            RETURNING leased_until
            """)
        .param("id", attemptId)
        .param("ttl", ttlSeconds)
        .query((rs, i) -> instant(rs, "leased_until"))
        .optional();
  }

  public Optional<Instant> currentLease(UUID attemptId) {
    return jdbc.sql("SELECT leased_until FROM attempts WHERE id = :id")
        .param("id", attemptId)
        .query((rs, i) -> instant(rs, "leased_until"))
        .optional();
  }

  public void insertCaseResult(
      UUID attemptId, WorkerApi.CaseResult result, ErrorClass errorClass, String message) {
    jdbc.sql(
            """
            INSERT INTO case_results (attempt_id, case_key, status, duration_ms, retry_count,
                                      error_class, error_message_redacted, start_offset_ms)
            VALUES (:a, :k, :s, :d, :r, :e, :m, :o)
            """)
        .param("a", attemptId)
        .param("k", result.caseKey())
        .param("s", result.status().name())
        .param("d", result.durationMs())
        .param("r", result.retryCount())
        .param("e", errorClass == null ? null : errorClass.name())
        .param("m", message)
        .param("o", result.startOffsetMs())
        .update();
  }

  /** PRD-06 FR-7: one row per accepted heartbeat, so the timeline can show gaps. */
  public void recordHeartbeat(UUID attemptId) {
    jdbc.sql("INSERT INTO attempt_heartbeats (attempt_id) VALUES (:a)")
        .param("a", attemptId)
        .update();
  }

  public int finishAttempt(
      UUID attemptId,
      AttemptStatus status,
      ErrorClass errorClass,
      String detail,
      String completionKey) {
    return jdbc.sql(
            """
            UPDATE attempts
            SET status = :s, error_class = :e, error_detail_redacted = :d, completion_key = :k,
                finished_at = now(), version = version + 1
            WHERE id = :id AND status IN ('CLAIMED', 'RUNNING')
            """)
        .param("id", attemptId)
        .param("s", status.name())
        .param("e", errorClass == null ? null : errorClass.name())
        .param("d", detail)
        .param("k", completionKey)
        .update();
  }

  public int finishTarget(UUID targetId, TargetStatus status) {
    return jdbc.sql(
            """
            UPDATE run_targets SET status = :s, finished_at = now(), version = version + 1
            WHERE id = :t AND status IN ('CLAIMED', 'RUNNING')
            """)
        .param("t", targetId)
        .param("s", status.name())
        .update();
  }

  /**
   * Takes the run row's FOR UPDATE lock now, before any {@code run_events} insert. Each such insert
   * takes FOR KEY SHARE on the run through its foreign key; asking for FOR UPDATE afterwards (as
   * {@link #recomputeRun} does) is a lock upgrade, and two transactions upgrading on the same run
   * deadlock. Call it right after locking the target, so the order stays target before run.
   */
  public void lockRun(UUID runId) {
    jdbc.sql("SELECT id FROM runs WHERE id = :r FOR UPDATE").param("r", runId).query().singleRow();
  }

  public record RunChange(RunStatus status, boolean finishedNow) {}

  /**
   * Contracts §2.3 / invariant I6: recompute {@code runs.status} from its targets in the caller's
   * transaction. The run row is locked first, so two targets of one run finishing at the same time
   * are serialised here and the second one sees the first one's committed status.
   */
  public RunChange recomputeRun(UUID runId) {
    RunStatus before =
        jdbc.sql("SELECT status FROM runs WHERE id = :r FOR UPDATE")
            .param("r", runId)
            .query((rs, i) -> RunStatus.valueOf(rs.getString("status")))
            .single();
    List<RunAggregate.TargetState> targets =
        jdbc.sql("SELECT status, attempt_count FROM run_targets WHERE run_id = :r")
            .param("r", runId)
            .query((rs, i) ->
                new RunAggregate.TargetState(
                    TargetStatus.valueOf(rs.getString("status")), rs.getInt("attempt_count")))
            .list();
    RunStatus after = RunAggregate.of(targets);
    if (after == before) {
      return new RunChange(after, false);
    }
    boolean terminal = after.isTerminal();
    int updated =
        jdbc.sql(
                """
                UPDATE runs
                SET status = :s,
                    started_at = CASE WHEN :s <> 'QUEUED' THEN coalesce(started_at, now())
                                      ELSE started_at END,
                    finished_at = CASE WHEN :terminal THEN now() ELSE NULL END,
                    version = version + 1
                WHERE id = :r
                  AND status NOT IN ('PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED')
                """)
            .param("r", runId)
            .param("s", after.name())
            .param("terminal", terminal)
            .update();
    if (updated != 1) {
      throw new IllegalStateException("run " + runId + " is already terminal (" + before + ")");
    }
    return new RunChange(after, terminal);
  }

  public void insertEvent(
      UUID runId, UUID targetId, UUID attemptId, EventType type, String payloadJson) {
    jdbc.sql(
            """
            INSERT INTO run_events (run_id, target_id, attempt_id, type, payload)
            VALUES (:r, :t, :a, :type, CAST(:payload AS jsonb))
            """)
        .param("r", runId)
        .param("t", targetId)
        .param("a", attemptId)
        .param("type", type.name())
        .param("payload", payloadJson)
        .update();
  }

  public record Outcome(
      AttemptStatus attemptStatus,
      ErrorClass errorClass,
      TargetStatus targetStatus,
      RunStatus runStatus) {}

  public Outcome outcome(UUID attemptId) {
    return jdbc.sql(
            """
            SELECT a.status AS attempt_status, a.error_class, t.status AS target_status,
                   r.status AS run_status
            FROM attempts a JOIN run_targets t ON t.id = a.target_id JOIN runs r ON r.id = t.run_id
            WHERE a.id = :id
            """)
        .param("id", attemptId)
        .query((rs, i) ->
            new Outcome(
                AttemptStatus.valueOf(rs.getString("attempt_status")),
                rs.getString("error_class") == null
                    ? null
                    : ErrorClass.valueOf(rs.getString("error_class")),
                TargetStatus.valueOf(rs.getString("target_status")),
                RunStatus.valueOf(rs.getString("run_status"))))
        .single();
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }
}

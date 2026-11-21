package dev.testgrid.api.run;

import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.TargetStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for {@code POST /runs/{runId}/cancel} (PRD-04 FR-8). Lock order is the same as everywhere
 * else: targets first (in id order), the run last. Claim, complete and the reaper all lock a target
 * before its run, so cancel can wait for them but never deadlock with them.
 */
@Repository
public class RunCancelRepository {

  private final JdbcClient jdbc;

  public RunCancelRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record LockedTarget(UUID id, TargetStatus status) {}

  /** Every target of the run inside the caller's project, locked; empty if no such run. */
  public List<LockedTarget> lockTargets(UUID projectId, UUID runId) {
    return jdbc.sql(
            """
            SELECT t.id, t.status
            FROM run_targets t
            JOIN runs r ON r.id = t.run_id
            WHERE t.run_id = :r AND r.project_id = :p
            ORDER BY t.id
            FOR UPDATE OF t
            """)
        .param("r", runId)
        .param("p", projectId)
        .query((rs, i) ->
            new LockedTarget(
                rs.getObject("id", UUID.class), TargetStatus.valueOf(rs.getString("status"))))
        .list();
  }

  public record LockedRun(RunStatus status, boolean cancelRequested) {}

  public Optional<LockedRun> lockRun(UUID projectId, UUID runId) {
    return jdbc.sql(
            """
            SELECT status, cancel_requested_at IS NOT NULL AS cancel_requested
            FROM runs WHERE id = :r AND project_id = :p
            FOR UPDATE
            """)
        .param("r", runId)
        .param("p", projectId)
        .query((rs, i) ->
            new LockedRun(
                RunStatus.valueOf(rs.getString("status")), rs.getBoolean("cancel_requested")))
        .optional();
  }

  public void markCancelRequested(UUID runId) {
    jdbc.sql(
            """
            UPDATE runs SET cancel_requested_at = now(), version = version + 1
            WHERE id = :r AND cancel_requested_at IS NULL
            """)
        .param("r", runId)
        .update();
  }

  /**
   * QUEUED targets end CANCELLED now, dispatched or not: a message already in the broker is then
   * refused by the claim ({@code 409}, the target is terminal) and acked.
   */
  public List<UUID> cancelQueuedTargets(UUID runId) {
    return jdbc.sql(
            """
            UPDATE run_targets
            SET status = 'CANCELLED', finished_at = now(), version = version + 1
            WHERE run_id = :r AND status = 'QUEUED'
            RETURNING id
            """)
        .param("r", runId)
        .query(UUID.class)
        .list();
  }
}

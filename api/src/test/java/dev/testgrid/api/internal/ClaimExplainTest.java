package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The targeted claim (PRD-04 FR-5) locks one target by primary key, so its cost doesn't grow with
 * the number of targets. With 5,000 targets the plan is an index scan on {@code run_targets_pkey}
 * under a row lock. The plan is written to target/evidence/ for docs/evidence/PRD-04.md.
 */
class ClaimExplainTest extends WorkerApiTestBase {

  private static final int ROWS = 5000;
  private static final Path EVIDENCE = Path.of("target/evidence/explain-target-claim.txt");

  @Test
  void theTargetedClaimLocksOneTargetByPrimaryKey() throws IOException {
    UUID suiteId =
        jdbc.sql("SELECT id FROM suites WHERE suite_key = 'checkout-smoke' AND is_current")
            .query(UUID.class)
            .single();
    jdbc.sql(
            """
            WITH r AS (
              INSERT INTO runs (project_id, suite_id, status, trigger, idempotency_key,
                                request_hash, queued_at, finished_at)
              SELECT '00000000-0000-4000-8000-000000000001', :s,
                     CASE WHEN g <= 10 THEN 'QUEUED' ELSE 'PASSED' END, 'CI', 'explain-' || g,
                     sha256(g::text::bytea), now() - (g || ' seconds')::interval,
                     CASE WHEN g <= 10 THEN NULL ELSE now() END
              FROM generate_series(1, :rows) AS g
              RETURNING id, status, queued_at, finished_at)
            INSERT INTO run_targets (run_id, target_key, executor, engine, status, queued_at,
                                     finished_at)
            SELECT id, 'local:chromium:0', 'LOCAL', 'chromium', status, queued_at, finished_at
            FROM r
            """)
        .param("s", suiteId)
        .param("rows", ROWS)
        .update();
    jdbc.sql("ANALYZE runs").update();
    jdbc.sql("ANALYZE run_targets").update();
    UUID target =
        jdbc.sql("SELECT id FROM run_targets WHERE status = 'QUEUED' LIMIT 1")
            .query(UUID.class)
            .single();

    String plan =
        String.join(
            "\n",
            jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + AttemptRepository.LOCK_FOR_CLAIM)
                .param("t", target)
                .query(String.class)
                .list());

    assertThat(plan).contains("using run_targets_pkey on run_targets");
    assertThat(plan).doesNotContain("Seq Scan on run_targets");
    assertThat(plan).contains("LockRows");

    Files.createDirectories(EVIDENCE.getParent());
    Files.writeString(
        EVIDENCE,
        "-- POST /internal/v1/targets/{targetId}/claim, "
            + ROWS
            + " targets (ANALYZEd)\n"
            + plan
            + "\n");
  }
}

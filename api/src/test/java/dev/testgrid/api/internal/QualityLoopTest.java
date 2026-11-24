package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * PRD-08 FR-2, CONFLICT_REGISTER N35: a run is evaluated after it finishes without anyone asking,
 * and the sweep evaluates a finished run whose after-commit nudge never arrived.
 */
@TestPropertySource(
    properties = {"testgrid.quality.loop-enabled=true", "testgrid.quality.sweep-interval-ms=200"})
class QualityLoopTest extends WorkerApiTestBase {

  private long evaluations(String runId) {
    return count("SELECT count(*) FROM quality_evaluations WHERE run_id = '" + runId + "'");
  }

  private void awaitEvaluation(String runId) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (evaluations(runId) == 0 && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
    }
  }

  @Test
  void aFinishedRunIsEvaluatedOnItsOwnAndOnlyOnce() throws InterruptedException {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    var done =
        complete(
            claim, "k-" + UUID.randomUUID(), "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    awaitEvaluation(runId);
    assertThat(evaluations(runId)).isEqualTo(1);
    assertThat(one("SELECT outcome FROM quality_evaluations WHERE run_id = :id", runId))
        .isEqualTo("PASS");
    Thread.sleep(600); // three more sweeps
    assertThat(evaluations(runId)).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM run_events WHERE type = 'QUALITY_EVALUATED' AND run_id = '"
                    + runId
                    + "'"))
        .isEqualTo(1);
  }

  @Test
  void theSweepEvaluatesAFinishedRunThatWasNeverSignalled() throws InterruptedException {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    // Finish it behind the API's back, as a crash between commit and the nudge would leave it.
    jdbc.sql(
            "UPDATE run_targets SET status = 'INFRA_ERROR', finished_at = now()"
                + " WHERE run_id = CAST(:id AS uuid)")
        .param("id", runId)
        .update();
    jdbc.sql(
            "UPDATE runs SET status = 'INFRA_ERROR', finished_at = now()"
                + " WHERE id = CAST(:id AS uuid)")
        .param("id", runId)
        .update();
    awaitEvaluation(runId);
    assertThat(one("SELECT outcome FROM quality_evaluations WHERE run_id = :id", runId))
        .isEqualTo("INDETERMINATE");
  }
}

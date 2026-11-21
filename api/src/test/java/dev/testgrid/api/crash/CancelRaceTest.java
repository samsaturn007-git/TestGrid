package dev.testgrid.api.crash;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.run.RunCancelService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AC-7 (Q-007) with a worker in the loop: the cancel reaches a running attempt through its
 * heartbeat and the worker completes CANCELLED; a cancel timed around the end of the attempt
 * resolves to one terminal state either way, with both requests in the event log. The pure
 * HTTP-level races (claim and completion against cancel, 10 runs each) are in RunCancelTest.
 */
class CancelRaceTest extends CrashTestBase {

  @Autowired RunCancelService cancels;

  @RepeatedTest(5)
  void aRunningAttemptIsStoppedThroughItsHeartbeat() throws Exception {
    SimWorker w = worker("w-a", 1, 10_000, ALL_ENGINES).start();
    UUID runId = createRun("chromium");
    UUID target = onlyTarget(runId);
    await(Duration.ofSeconds(10), () -> "RUNNING".equals(targetStatus(target)), "running");

    long asked = System.currentTimeMillis();
    assertThat(cancels.cancel(runId).accepted()).isTrue();
    await(Duration.ofSeconds(5), () -> terminal(target), "target terminal");
    long stopped = System.currentTimeMillis() - asked;

    assertThat(targetStatus(target)).isEqualTo("CANCELLED");
    assertThat(runStatus(runId)).isEqualTo("CANCELLED");
    assertThat(attemptsOf(target)).containsExactly("1:CANCELLED:CANCELLED");
    assertThat(stopped).as("ms until the worker stopped (heartbeat every 1 s)").isLessThan(3000);
    EvidenceRecorder.note("stopped " + stopped + " ms after the cancel; worker " + w.summary());
  }

  /**
   * The attempt runs 1.5 s with a heartbeat at 1 s. A cancel at +600/+800 ms is seen by that
   * heartbeat (CANCELLED); one at +1200/+1400 ms lands after it, so the completion with results
   * commits first (PASSED; the cancel was accepted while the attempt was still RUNNING). +1000 ms
   * races the heartbeat itself.
   */
  @RepeatedTest(5)
  void aCancelAtTheEndOfTheAttemptResolvesToOneTerminalState(RepetitionInfo rep) throws Exception {
    worker("w-a", 1, 1500, ALL_ENGINES).start();
    UUID runId = createRun("chromium");
    UUID target = onlyTarget(runId);
    await(Duration.ofSeconds(10), () -> "RUNNING".equals(targetStatus(target)), "running");
    Thread.sleep(600 + (rep.getCurrentRepetition() - 1) * 200L);

    var result = cancels.cancel(runId);
    await(Duration.ofSeconds(5), () -> terminal(target), "target terminal");

    String status = targetStatus(target);
    assertThat(status).isIn("PASSED", "CANCELLED");
    assertThat(count("SELECT count(*) FROM run_events WHERE type = 'TARGET_FINISHED'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM run_events WHERE type = 'RUN_CANCEL_REQUESTED'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM attempts")).isEqualTo(1);
    EvidenceRecorder.note(
        "cancel at +"
            + (600 + (rep.getCurrentRepetition() - 1) * 200)
            + " ms: accepted="
            + result.accepted()
            + ", target "
            + status);
  }
}

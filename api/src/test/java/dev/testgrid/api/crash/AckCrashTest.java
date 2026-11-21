package dev.testgrid.api.crash;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;

/**
 * AC-3, second half (Q-008): the worker completes, then dies before acking. The broker redelivers
 * the message to the restarted worker; the claim answers 409 (terminal) and it is acked. No second
 * attempt.
 */
class AckCrashTest extends CrashTestBase {

  @RepeatedTest(5)
  void aCrashAfterCompleteBeforeAckLeavesOneAttempt() throws Exception {
    SimWorker w =
        worker("w-a", 1, 300, ALL_ENGINES)
            .withFault(SimWorker.Fault.AFTER_COMPLETE_BEFORE_ACK, 1)
            .start();
    UUID target = onlyTarget(createRun("chromium"));

    await(
        Duration.ofSeconds(15),
        () -> w.summary().getOrDefault("claim 409", 0) == 1,
        "the redelivery is refused and acked");

    assertThat(w.crashes.get()).isEqualTo(1);
    assertThat(targetStatus(target)).isEqualTo("PASSED");
    assertThat(attemptsOf(target)).containsExactly("1:PASSED:-");
    await(Duration.ofSeconds(5), () -> depth("tg.targets.chromium") == 0, "queue drained");
    EvidenceRecorder.note("worker " + w.summary());
  }
}

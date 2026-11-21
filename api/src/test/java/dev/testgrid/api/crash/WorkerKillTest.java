package dev.testgrid.api.crash;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;

/**
 * AC-4 (Q-004, invariants I3 and I8): a worker dies after claiming. Its attempt is ABANDONED and
 * kept; the target is retried or ends INFRA_ERROR within {@code max_attempts × (TTL + backoff)}.
 */
class WorkerKillTest extends CrashTestBase {

  /** 3 × (5 s TTL + 1 s max backoff) = 18 s. */
  static final long BOUND_MS = MAX_ATTEMPTS * (LEASE_TTL_S * 1000L + MAX_BACKOFF_MS);

  @RepeatedTest(5)
  void aKilledWorkersTargetIsRetriedAndTheAbandonedAttemptIsKept() throws Exception {
    SimWorker w =
        worker("w-a", 1, 300, ALL_ENGINES).withFault(SimWorker.Fault.AFTER_CLAIM, 1).start();
    UUID target = onlyTarget(createRun("chromium"));
    await(Duration.ofSeconds(10), () -> w.crashes.get() == 1, "the worker died after its claim");
    long crashed = System.currentTimeMillis();

    await(Duration.ofMillis(BOUND_MS + 5000), () -> terminal(target), "target terminal");
    long recovered = System.currentTimeMillis() - crashed;

    assertThat(targetStatus(target)).isEqualTo("PASSED");
    assertThat(attemptsOf(target)).containsExactly("1:ABANDONED:LEASE_EXPIRED", "2:PASSED:-");
    assertThat(
            count(
                "SELECT count(*) FROM case_results c JOIN attempts a ON a.id = c.attempt_id"
                    + " WHERE a.status = 'ABANDONED'"))
        .isZero();
    // The restarted worker got the old message back: refused, a new generation came later.
    assertThat(w.summary()).containsEntry("claim 409", 1);
    assertThat(recovered).as("recovery ms").isLessThanOrEqualTo(BOUND_MS);
    EvidenceRecorder.note(
        "recovered " + recovered + " ms after the crash (bound " + BOUND_MS + ")");
  }

  @RepeatedTest(5)
  void aTargetWhoseWorkerAlwaysDiesEndsInfraErrorWithinTheBound() throws Exception {
    SimWorker w =
        worker("w-a", 1, 300, ALL_ENGINES).withFault(SimWorker.Fault.AFTER_CLAIM, 3).start();
    UUID runId = createRun("chromium");
    UUID target = onlyTarget(runId);
    await(Duration.ofSeconds(10), () -> w.crashes.get() == 1, "first crash");
    long first = System.currentTimeMillis();

    await(Duration.ofMillis(BOUND_MS + 5000), () -> terminal(target), "target terminal");
    long took = System.currentTimeMillis() - first;

    assertThat(targetStatus(target)).isEqualTo("INFRA_ERROR");
    assertThat(runStatus(runId)).isEqualTo("INFRA_ERROR");
    assertThat(attemptsOf(target))
        .containsExactly(
            "1:ABANDONED:LEASE_EXPIRED", "2:ABANDONED:LEASE_EXPIRED", "3:ABANDONED:LEASE_EXPIRED");
    assertThat(took).as("ms from the first crash to INFRA_ERROR").isLessThanOrEqualTo(BOUND_MS);
    EvidenceRecorder.note(
        "INFRA_ERROR " + took + " ms after the first crash (bound " + BOUND_MS + ")");
  }
}

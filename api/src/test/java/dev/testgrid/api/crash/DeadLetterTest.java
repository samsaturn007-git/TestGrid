package dev.testgrid.api.crash;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.dispatch.Topology;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;

/**
 * AC-6 (Q-009): every worker that receives the message dies before claiming it. Each dispatch is
 * redelivered until the quorum queue's delivery limit dead-letters it; the reaper's dispatch
 * timeout sends a new generation; after {@code max_attempts} unclaimed dispatches the target is
 * INFRA_ERROR (WORKER_LOST) and the dead-letter queue holds every copy. The DLQ depth metric is
 * PRD-12 (ARCHITECTURE §8); this test reads the depth from the broker.
 */
class DeadLetterTest extends CrashTestBase {

  @RepeatedTest(5)
  void exhaustedDispatchesEndInfraErrorWithTheMessagesInTheDeadLetterQueue() throws Exception {
    SimWorker w =
        worker("w-a", 1, 300, ALL_ENGINES).withFault(SimWorker.Fault.ON_RECEIVE, 1000).start();
    UUID runId = createRun("chromium");
    UUID target = onlyTarget(runId);

    await(Duration.ofSeconds(40), () -> terminal(target), "target terminal");
    await(
        Duration.ofSeconds(5),
        () -> depth(Topology.DEAD_QUEUE) == MAX_ATTEMPTS,
        "one dead letter per dispatch");

    assertThat(targetStatus(target)).isEqualTo("INFRA_ERROR");
    assertThat(runStatus(runId)).isEqualTo("INFRA_ERROR");
    assertThat(count("SELECT count(*) FROM attempts")).isZero();
    assertThat(
            jdbc.sql("SELECT payload->>'errorClass' FROM run_events WHERE type = 'TARGET_FINISHED'")
                .query(String.class)
                .single())
        .isEqualTo("WORKER_LOST");
    assertThat(
            count(
                "SELECT count(*) FROM run_events WHERE type = 'TARGET_RETRY_SCHEDULED'"
                    + " AND payload->>'reason' = 'DISPATCH_TIMEOUT'"))
        .isEqualTo(MAX_ATTEMPTS - 1);
    // 1 delivery + 5 redeliveries per message (x-delivery-limit 5).
    assertThat(w.crashes.get()).isEqualTo(MAX_ATTEMPTS * (Topology.DELIVERY_LIMIT + 1));
    EvidenceRecorder.note(
        "DLQ depth " + depth(Topology.DEAD_QUEUE) + ", worker crashes " + w.crashes.get());
  }
}

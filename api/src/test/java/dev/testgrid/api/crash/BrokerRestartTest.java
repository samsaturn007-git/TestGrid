package dev.testgrid.api.crash;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;

/**
 * AC-5 (Q-006, invariant I7): {@code docker restart} of RabbitMQ while targets are running and
 * waiting. Running attempts keep their leases through the database; their acks are lost with the
 * old connection, so the broker redelivers and the claim answers 409. Dispatches whose publish
 * failed stay in the outbox and go out once the broker is back. Every committed target reaches a
 * terminal state.
 */
class BrokerRestartTest extends CrashTestBase {

  @RepeatedTest(5)
  void aBrokerRestartMidRunLosesNoTarget() throws Exception {
    SimWorker w = worker("w-a", 2, 1500, ALL_ENGINES).start();
    List<UUID> runs =
        List.of(
            createRun("chromium", "firefox", "webkit"), createRun("chromium", "firefox", "webkit"));
    await(
        Duration.ofSeconds(15),
        () -> count("SELECT count(*) FROM attempts WHERE status = 'RUNNING'") >= 1,
        "attempts running");

    long started = System.currentTimeMillis();
    rabbit.getDockerClient().restartContainerCmd(rabbit.getContainerId()).withTimeout(10).exec();
    long restartMs = System.currentTimeMillis() - started;

    await(
        Duration.ofSeconds(90),
        () -> count("SELECT count(*) FROM run_targets WHERE finished_at IS NOT NULL") == 6,
        "all six targets terminal");
    long settled = System.currentTimeMillis() - started;

    assertThat(count("SELECT count(*) FROM run_targets WHERE status = 'PASSED'")).isEqualTo(6);
    assertThat(runs).allSatisfy(r -> assertThat(runStatus(r)).isEqualTo("PASSED"));
    assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
    // No lease expired: the database, not the broker, holds the attempt.
    assertThat(count("SELECT count(*) FROM attempts WHERE status = 'ABANDONED'")).isZero();
    assertThat(count("SELECT count(*) FROM attempts")).isEqualTo(6);
    long retriedPublishes = count("SELECT count(*) FROM outbox_events WHERE publish_attempts > 1");
    EvidenceRecorder.note(
        "docker restart took "
            + restartMs
            + " ms; all 6 PASSED "
            + settled
            + " ms after it began; outbox rows that needed a retry: "
            + retriedPublishes
            + "; worker "
            + w.summary());
  }
}

package dev.testgrid.api.crash;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.dispatch.FaultInjector;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;

/**
 * AC-3, first half (Q-003): the API "dies" after the broker confirmed a publish and before the
 * outbox row was marked ({@code TG_FAULT=after_publish_before_mark}: the transaction rolls back
 * after the confirm). The next poll publishes the same message again; the duplicate is fenced by
 * the claim.
 */
class PublishCrashTest extends CrashTestBase {

  @RepeatedTest(5)
  void aCrashBetweenPublishAndMarkIsHarmless() throws Exception {
    faults.arm(FaultInjector.Point.AFTER_PUBLISH_BEFORE_MARK);
    SimWorker w = worker("w-a", 1, 300, ALL_ENGINES).start();
    UUID target = onlyTarget(createRun("chromium"));

    await(Duration.ofSeconds(15), () -> terminal(target), "target terminal");
    await(
        Duration.ofSeconds(5),
        () -> w.summary().getOrDefault("claim 409", 0) == 1,
        "the re-published copy is refused");

    assertThat(targetStatus(target)).isEqualTo("PASSED");
    assertThat(attemptsOf(target)).containsExactly("1:PASSED:-");
    var outbox =
        jdbc.sql(
                "SELECT published_at IS NOT NULL AS published, publish_attempts FROM outbox_events")
            .query()
            .singleRow();
    // The failed try rolled back with the fault, so only the successful one is counted.
    assertThat(outbox).containsEntry("published", true).containsEntry("publish_attempts", 1);
    EvidenceRecorder.note("worker " + w.summary());
  }
}

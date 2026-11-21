package dev.testgrid.api.crash;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;

/**
 * AC-1 (Q-001, invariant I5): 20 targets, cap 3, more worker slots (4) than the cap, so the cap is
 * what limits. Active sessions are sampled every 25 ms (the PRD asks for 200 ms; denser is
 * stricter) straight from the database.
 */
class CapTest extends CrashTestBase {

  @RepeatedTest(5)
  void twentyTargetsWithCapThreeNeverRunMoreThanThree() throws Exception {
    worker("w-a", 2, 300, ALL_ENGINES).start();
    worker("w-b", 2, 300, ALL_ENGINES).start();
    AtomicInteger max = new AtomicInteger();
    AtomicInteger samples = new AtomicInteger();
    AtomicBoolean sampling = new AtomicBoolean(true);
    Thread sampler =
        Thread.ofPlatform()
            .start(() -> {
              while (sampling.get()) {
                int active =
                    (int)
                        count(
                            "SELECT count(*) FROM attempts"
                                + " WHERE status IN ('CLAIMED', 'RUNNING')");
                max.accumulateAndGet(active, Math::max);
                samples.incrementAndGet();
                try {
                  Thread.sleep(25);
                } catch (InterruptedException e) {
                  return;
                }
              }
            });

    long started = System.currentTimeMillis();
    List<UUID> runs = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      runs.add(createRun("chromium", "firefox", "webkit"));
    }
    runs.add(createRun("chromium", "firefox"));
    await(
        Duration.ofSeconds(60),
        () -> count("SELECT count(*) FROM run_targets WHERE status = 'PASSED'") == 20,
        "all 20 targets PASSED");
    long took = System.currentTimeMillis() - started;
    sampling.set(false);
    sampler.join();

    assertThat(max.get()).as("most active sessions seen").isLessThanOrEqualTo(3);
    assertThat(max.get()).as("the cap was actually reached").isEqualTo(3);
    assertThat(count("SELECT count(*) FROM attempts")).isEqualTo(20);
    assertThat(runs).allSatisfy(r -> assertThat(runStatus(r)).isEqualTo("PASSED"));
    EvidenceRecorder.note(
        "max active "
            + max.get()
            + " over "
            + samples.get()
            + " samples; 20 targets in "
            + took
            + " ms");
  }
}

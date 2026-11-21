package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Regression for a deadlock found by the PRD-04 T6 crash tests: completions of different targets of
 * the same run each wrote events (a run_events foreign key takes FOR KEY SHARE on the run row) and
 * then asked for FOR UPDATE on the run to recompute it. Two of them held the weak lock and waited
 * for the strong one; PostgreSQL aborted one with "deadlock detected". The completion now locks the
 * run before it writes anything.
 */
class ConcurrentCompletionTest extends WorkerApiTestBase {

  @RepeatedTest(10)
  void threeTargetsOfOneRunCompleteAtOnceWithoutADeadlock() throws Exception {
    String runId = createRun("checkout-smoke", "PASS", "chromium", "firefox", "webkit");
    List<Claim> claims = new ArrayList<>();
    for (String engine : List.of("chromium", "firefox", "webkit")) {
      Claim claim = claim(engine);
      start(claim);
      claims.add(claim);
    }

    CountDownLatch go = new CountDownLatch(1);
    List<HttpResponse<String>> responses = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (Claim claim : claims) {
        futures.add(
            pool.submit(() -> {
              go.await();
              return complete(claim, "k", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));
            }));
      }
      go.countDown();
      for (var f : futures) {
        responses.add(f.get());
      }
    }

    assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
    assertThat(one("SELECT status FROM runs WHERE id = :id", runId)).isEqualTo("PASSED");
    assertThat(count("SELECT count(*) FROM run_events WHERE type = 'RUN_FINISHED'")).isEqualTo(1);
  }
}
